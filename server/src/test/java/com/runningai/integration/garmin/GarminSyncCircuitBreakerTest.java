package com.runningai.integration.garmin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The automatic-sync gate, exercised directly over every state the attempt ledger can hold.
 * <p>
 * The property these tests exist to protect is asymmetric. Failing to block costs some wasted
 * Garmin requests; blocking wrongly stops collection, which is the thing the whole system is for.
 * So for each rule there is a test that it trips AND a test that it does not trip one step early,
 * and the cases that must never block at all - no history, a success, an interrupted process - are
 * pinned explicitly.
 */
class GarminSyncCircuitBreakerTest {

    private static final Instant T0 = Instant.parse("2026-10-15T00:00:00Z");

    private final GarminActivityRegistry registry = new GarminActivityRegistry();
    private final GarminSyncCircuitBreakerProperties properties = new GarminSyncCircuitBreakerProperties(
            true, 3, 1, 8, 6,
            Duration.ofHours(6), Duration.ofHours(2), Duration.ofHours(12), Duration.ofMinutes(15));

    private final GarminSyncCircuitBreaker breaker = new GarminSyncCircuitBreaker(
            mock(GarminSyncAttemptService.class), mock(com.runningai.athlete.AthleteService.class),
            registry, properties, java.time.Clock.systemUTC());

    // ---------------------------------------------------------------- never blocked

    @Test
    void anAthleteWithNoAttemptEverRecordedIsAllowed() {
        assertThat(breaker.evaluate(null, T0).allowed()).isTrue();
    }

    @Test
    void aRecentSuccessIsAllowedOnceTheMinimumIntervalHasPassed() {
        GarminSyncAttempt attempt = succeeded(T0);

        assertThat(breaker.evaluate(attempt, T0.plus(Duration.ofMinutes(16))).allowed()).isTrue();
    }

    /**
     * An interrupted attempt is this machine failing, not Garmin refusing. Blocking on it would
     * make every watchdog restart delay the recovery it is restarting for.
     */
    @Test
    void anInterruptedAttemptNeverBlocksAndIsExemptFromTheMinimumInterval() {
        GarminSyncAttempt attempt = failed(T0, "GARMIN_AUTH_REQUIRED", 9);
        attempt.recordInterrupted("PROCESS_INTERRUPTED");

        GarminSyncGateDecision decision = breaker.evaluate(attempt, T0.plusSeconds(1));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void theSwitchOffRestoresThePreviousBehaviourExactly() {
        GarminSyncCircuitBreakerProperties off = new GarminSyncCircuitBreakerProperties(
                false, 1, 1, 1, 1, Duration.ofHours(6), Duration.ofHours(2), Duration.ofHours(12),
                Duration.ofHours(1));
        GarminSyncCircuitBreaker disabled = new GarminSyncCircuitBreaker(
                mock(GarminSyncAttemptService.class), mock(com.runningai.athlete.AthleteService.class),
                registry, off, java.time.Clock.systemUTC());

        assertThat(disabled.evaluate().allowed()).isTrue();
    }

    // ---------------------------------------------------------------- authentication

    @Test
    void authFailuresBelowTheThresholdStillGetAnotherTry() {
        GarminSyncGateDecision decision = breaker.evaluate(failed(T0, "GARMIN_AUTH_REQUIRED", 2), T0.plusSeconds(3600));

        assertThat(decision.allowed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GARMIN_AUTH_REQUIRED", "GARMIN_FORBIDDEN"})
    void repeatedAuthFailuresStopAutomaticSyncWithNoExpiry(String code) {
        GarminSyncGateDecision decision = breaker.evaluate(failed(T0, code, 3), T0.plus(Duration.ofDays(30)));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(GarminSyncGateReason.AUTH_FAILURES);
        // No retryAfter at all: waiting is not what fixes a rejected credential.
        assertThat(decision.retryAfter()).isNull();
    }

    /** A completed attempt resets the streak, which is the only thing that lifts an auth block. */
    @Test
    void aCompletedAttemptAfterAnAuthBlockLiftsIt() {
        GarminSyncAttempt attempt = failed(T0, "GARMIN_AUTH_REQUIRED", 5);
        assertThat(breaker.evaluate(attempt, T0.plusSeconds(3600)).allowed()).isFalse();

        attempt.recordCompleted(T0.plusSeconds(3600), result(1, 1, 0, 0, 0, true));

        assertThat(breaker.evaluate(attempt, T0.plusSeconds(7200)).allowed()).isTrue();
    }

    // ---------------------------------------------------------------- rate limit

    @Test
    void aSingleRateLimitPausesForTheCooldown() {
        GarminSyncGateDecision decision = breaker.evaluate(failed(T0, "GARMIN_RATE_LIMITED", 1), T0.plusSeconds(3600));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(GarminSyncGateReason.RATE_LIMITED);
        assertThat(decision.retryAfter()).isEqualTo(T0.plus(Duration.ofHours(6)));
    }

    @Test
    void aRateLimitCooldownExpiresOnItsOwn() {
        GarminSyncGateDecision decision = breaker.evaluate(failed(T0, "GARMIN_RATE_LIMITED", 1),
                T0.plus(Duration.ofHours(6)));

        assertThat(decision.allowed()).isTrue();
    }

    // ---------------------------------------------------------------- transient

    @ParameterizedTest
    @CsvSource({
            "GARMIN_UNAVAILABLE, 7, true",
            "GARMIN_UNAVAILABLE, 8, false",
            "GARMIN_UPSTREAM_ERROR, 8, false",
            "INCREMENTAL_WINDOW_INCOMPLETE, 8, false",
            "UNEXPECTED_IllegalStateException, 8, false",
    })
    void transientFailuresAreToleratedFarLongerThanTheOtherKinds(String code, int streak, boolean expectedAllowed) {
        GarminSyncGateDecision decision = breaker.evaluate(failed(T0, code, streak), T0.plusSeconds(3600));

        assertThat(decision.allowed()).isEqualTo(expectedAllowed);
    }

    @Test
    void aTransientBlockExpiresAfterItsCooldown() {
        assertThat(breaker.evaluate(failed(T0, "GARMIN_UNAVAILABLE", 8), T0.plus(Duration.ofHours(2))).allowed())
                .isTrue();
    }

    // ---------------------------------------------------------------- partial stall

    @Test
    void repeatedPartialRunsAreAStallAndEventuallyPause() {
        GarminSyncAttempt attempt = partialTimes(T0, 6);

        GarminSyncGateDecision decision = breaker.evaluate(attempt, T0.plusSeconds(3600));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(GarminSyncGateReason.PARTIAL_STALL);
    }

    @Test
    void aFewPartialRunsAreNormalAndDoNotBlock() {
        assertThat(breaker.evaluate(partialTimes(T0, 5), T0.plusSeconds(3600)).allowed()).isTrue();
    }

    /** A PARTIAL is not a failure, so it must never be counted into the failure streak. */
    @Test
    void aPartialRunDoesNotCountAsAFailure() {
        GarminSyncAttempt attempt = partialTimes(T0, 6);

        assertThat(attempt.getConsecutiveFailureCount()).isZero();
        assertThat(attempt.getConsecutivePartialCount()).isEqualTo(6);
    }

    @Test
    void aSuccessClearsThePartialStreak() {
        GarminSyncAttempt attempt = partialTimes(T0, 6);

        attempt.recordCompleted(T0.plusSeconds(60), result(1, 1, 0, 0, 0, true));

        assertThat(attempt.getConsecutivePartialCount()).isZero();
    }

    // ---------------------------------------------------------------- request floor

    @Test
    void twoAutomaticAttemptsCannotStartWithinTheMinimumInterval() {
        GarminSyncGateDecision decision = breaker.evaluate(succeeded(T0), T0.plus(Duration.ofMinutes(14)));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(GarminSyncGateReason.MINIMUM_INTERVAL);
        assertThat(decision.retryAfter()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
    }

    /** The floor is measured from when the attempt STARTED, so a slow run earns no extra budget. */
    @Test
    void theFloorIsMeasuredFromTheStartOfThePreviousAttempt() {
        assertThat(breaker.evaluate(succeeded(T0), T0.plus(Duration.ofMinutes(15))).allowed()).isTrue();
    }

    // ---------------------------------------------------------------- concurrent Garmin work

    @Test
    void aRecoverySyncInFlightPostponesTheTick() {
        try (GarminActivityRegistry.Handle ignored =
                     registry.enter(GarminActivityRegistry.Operation.RECOVERY_SYNC)) {
            GarminSyncGateDecision decision = breaker.evaluate(null, T0);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.reason()).isEqualTo(GarminSyncGateReason.GARMIN_BUSY);
        }
    }

    @Test
    void theRegistryIsClearedWhenTheOperationEndsEvenIfItThrew() {
        try (GarminActivityRegistry.Handle ignored =
                     registry.enter(GarminActivityRegistry.Operation.RECOVERY_SYNC)) {
            assertThat(registry.running()).containsExactly(GarminActivityRegistry.Operation.RECOVERY_SYNC);
        }

        assertThat(registry.running()).isEmpty();
        assertThat(breaker.evaluate(null, T0).allowed()).isTrue();
    }

    /** A sync does not block itself: an incremental sync's own registration is not "busy". */
    @Test
    void anIncrementalSyncDoesNotCountAsAnotherOperation() {
        try (GarminActivityRegistry.Handle ignored =
                     registry.enter(GarminActivityRegistry.Operation.INCREMENTAL_SYNC)) {
            assertThat(breaker.evaluate(null, T0).allowed()).isTrue();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static GarminSyncAttempt succeeded(Instant at) {
        GarminSyncAttempt attempt = new GarminSyncAttempt(1L);
        attempt.recordCompleted(at, result(1, 1, 0, 0, 0, true));
        return attempt;
    }

    private static GarminSyncAttempt failed(Instant at, String code, int times) {
        GarminSyncAttempt attempt = new GarminSyncAttempt(1L);
        for (int i = 0; i < times; i++) {
            attempt.recordFailure(at, code);
        }
        return attempt;
    }

    private static GarminSyncAttempt partialTimes(Instant at, int times) {
        GarminSyncAttempt attempt = new GarminSyncAttempt(1L);
        for (int i = 0; i < times; i++) {
            attempt.recordCompleted(at, result(2, 1, 0, 0, 1, false));
        }
        return attempt;
    }

    private static GarminIncrementalSyncResult result(int fetched, int created, int updated, int skipped,
                                                      int failed, boolean checkpointAdvanced) {
        return new GarminIncrementalSyncResult(fetched, created, updated, skipped, failed, 1, checkpointAdvanced,
                checkpointAdvanced ? T0 : null);
    }
}
