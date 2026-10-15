package com.runningai.integration.garmin;

import com.runningai.athlete.AthleteService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides whether the AUTOMATIC Garmin sync may start (Phase 8B, Workstream A).
 *
 * <h3>Why this exists</h3>
 * Phase 8A gave the database a record of every sync attempt, but nothing read it. With a
 * fixed-delay of one hour that meant a rejected Garmin session produced 24 authentication failures
 * a day, indefinitely, with no one the wiser - the ledger counted them and did nothing. This turns
 * that count into a decision.
 *
 * <h3>What it may and may not block</h3>
 * It governs {@link GarminSyncScheduler} and nothing else. A manual
 * {@code POST /api/v1/garmin/sync} is never blocked, because:
 * <ul>
 *   <li>a human asking for a sync IS the intervention the breaker is waiting for, and making them
 *       clear a flag first would only add a step before the same request;</li>
 *   <li>the manual path is how a repaired credential gets proven repaired - and a completed
 *       attempt resets the streaks, which is what lifts an auth block;</li>
 *   <li>an automatic loop is what endangers the Garmin account; one deliberate request is not.</li>
 * </ul>
 * It therefore cannot stop a sync that would have succeeded; the worst it can do is delay an
 * automatic attempt, and a delayed incremental sync loses no data because the next one re-reads a
 * 7-day overlap.
 *
 * <h3>How a block lifts</h3>
 * Time-based blocks (rate limit, transient, partial stall) expire on their own. An AUTH block does
 * not: 401/403 will still be 401/403 in an hour, so it is lifted only by an attempt that completes -
 * in practice a manual sync once the connector's Garmin session has been renewed. Nothing is stored
 * to represent a block; every decision is derived from the ledger and the clock, so there is no
 * second copy of the truth to go stale and no migration is needed to change a threshold.
 */
@Service
public class GarminSyncCircuitBreaker {

    private final GarminSyncAttemptService attemptService;
    private final AthleteService athleteService;
    private final GarminActivityRegistry registry;
    private final GarminSyncCircuitBreakerProperties properties;
    private final Clock clock;

    public GarminSyncCircuitBreaker(GarminSyncAttemptService attemptService,
                                    AthleteService athleteService,
                                    GarminActivityRegistry registry,
                                    GarminSyncCircuitBreakerProperties properties,
                                    Clock clock) {
        this.attemptService = attemptService;
        this.athleteService = athleteService;
        this.registry = registry;
        this.properties = properties;
        this.clock = clock;
    }

    /** The decision for the default athlete, as the scheduler and the status endpoint both see it. */
    public GarminSyncGateDecision evaluate() {
        if (!properties.enabled()) {
            return GarminSyncGateDecision.allow();
        }
        Long athleteId = athleteService.getDefaultAthlete().getId();
        return evaluate(attemptService.find(athleteId).orElse(null), clock.instant());
    }

    /**
     * The whole policy, as a function of the ledger row and the current time. Package-private and
     * free of Spring so it can be exercised directly over every state the ledger can hold.
     */
    GarminSyncGateDecision evaluate(GarminSyncAttempt attempt, Instant now) {
        if (registry.busyOtherThan(GarminActivityRegistry.Operation.INCREMENTAL_SYNC)) {
            return GarminSyncGateDecision.blocked(GarminSyncGateReason.GARMIN_BUSY,
                    "Another Garmin operation is running; this tick is skipped", null);
        }
        if (attempt == null) {
            return GarminSyncGateDecision.allow();     // nothing has ever run: never block the first one
        }

        // A streak of FAILED attempts, judged by the kind of failure the LAST one was. The class of
        // the current streak is what matters, which is why failureCode (this attempt) is read and
        // not lastFailureCode (the historical "when did this last break", which outlives a success).
        if (attempt.getOutcome() == GarminSyncAttemptOutcome.FAILED) {
            Optional<GarminSyncGateDecision> blocked = failureBlock(attempt, now);
            if (blocked.isPresent()) {
                return blocked.get();
            }
        }

        if (attempt.getOutcome() == GarminSyncAttemptOutcome.PARTIAL
                && attempt.getConsecutivePartialCount() >= properties.partialThreshold()) {
            Instant until = attempt.getAttemptedAt().plus(properties.partialCooldown());
            if (now.isBefore(until)) {
                return GarminSyncGateDecision.blocked(GarminSyncGateReason.PARTIAL_STALL,
                        attempt.getConsecutivePartialCount() + " consecutive runs left items unmapped and the "
                                + "checkpoint unmoved; pausing automatic sync until the same window stops repeating",
                        until);
            }
        }

        // The floor on request frequency, applied last so a more informative block always wins the
        // explanation. attemptedAt is when the attempt STARTED, so a long run does not earn extra
        // allowance by finishing late. An INTERRUPTED attempt is exempt: its timestamp belongs to a
        // run the process never finished, and making a restarted server wait on it would delay
        // recovery for no benefit.
        Instant earliestNext = attempt.getAttemptedAt().plus(properties.minimumInterval());
        if (attempt.getOutcome() != GarminSyncAttemptOutcome.INTERRUPTED && now.isBefore(earliestNext)) {
            return GarminSyncGateDecision.blocked(GarminSyncGateReason.MINIMUM_INTERVAL,
                    "The previous attempt started less than " + describe(properties.minimumInterval()) + " ago",
                    earliestNext);
        }
        return GarminSyncGateDecision.allow();
    }

    private Optional<GarminSyncGateDecision> failureBlock(GarminSyncAttempt attempt, Instant now) {
        int streak = attempt.getConsecutiveFailureCount();
        GarminFailureClass failureClass = GarminFailureClass.of(attempt.getFailureCode());
        return switch (failureClass) {
            case AUTH -> streak >= properties.authFailureThreshold()
                    ? Optional.of(GarminSyncGateDecision.blocked(GarminSyncGateReason.AUTH_FAILURES,
                            streak + " consecutive Garmin authentication failures; automatic sync is stopped until "
                                    + "the connector's Garmin session is renewed and one sync completes", null))
                    : Optional.empty();
            case RATE_LIMIT -> cooldownBlock(attempt, now, streak, properties.rateLimitFailureThreshold(),
                    properties.rateLimitCooldown(), GarminSyncGateReason.RATE_LIMITED,
                    "Garmin rate-limited the last attempt");
            case TRANSIENT, UNKNOWN -> cooldownBlock(attempt, now, streak, properties.transientFailureThreshold(),
                    properties.transientCooldown(), GarminSyncGateReason.TRANSIENT_FAILURES,
                    streak + " consecutive connector or upstream failures");
        };
    }

    private Optional<GarminSyncGateDecision> cooldownBlock(GarminSyncAttempt attempt, Instant now, int streak,
                                                          int threshold, Duration cooldown,
                                                          GarminSyncGateReason reason, String detail) {
        if (streak < threshold) {
            return Optional.empty();
        }
        Instant until = attempt.getAttemptedAt().plus(cooldown);
        if (!now.isBefore(until)) {
            return Optional.empty();
        }
        return Optional.of(GarminSyncGateDecision.blocked(reason,
                detail + "; automatic sync pauses for " + describe(cooldown), until));
    }

    private static String describe(Duration d) {
        return d.toMinutes() < 60 ? d.toMinutes() + " minutes" : d.toHours() + " hours";
    }
}
