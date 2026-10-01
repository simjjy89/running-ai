package com.runningai.integration.intervals;

import com.runningai.integration.intervals.IntervalsException.Reason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishes a {@link RenderedIntervalsWorkout} to the Intervals.icu calendar idempotently and verifies the
 * server state afterwards. The rendered text is sent as the event description unchanged; nothing is
 * recomputed here (pace, heart rate and treadmill cues belong to the renderer).
 *
 * <h3>Logical identity and ownership</h3>
 * One RunningAI workout per athlete and calendar date. The marker is the event's {@code external_id}:
 * {@code runningai:workout:v1:<athleteId>:<yyyy-MM-dd>}. Keeping it out of the description means the
 * description is exactly the rendered text and can be compared and verified byte for byte.
 * For migration, an event with no {@code external_id} whose description carries the legacy
 * {@code [RunningAI-Control]} marker is also recognised as RunningAI-owned (see {@link #LEGACY_MARKER}); it is
 * updated in place (taking over the new marker) rather than duplicated. Events RunningAI does not own are never
 * modified.
 *
 * <h3>State machine</h3>
 * list the date's WORKOUT events, then: 2+ owned → {@code DUPLICATE_OWNED_WORKOUT} (nothing is touched);
 * 1 owned and equal → {@code NO_CHANGE} (no write); 1 owned and different → PUT the same event id
 * ({@code UPDATED}); 0 owned and only foreign events → {@code UNMANAGED_WORKOUT_CONFLICT} (as the legacy
 * publisher did); 0 owned and none → POST ({@code CREATED}). After every write the event is read back and
 * checked for marker, date and workout text; any difference is {@code READBACK_MISMATCH}, never a success.
 *
 * <h3>Retry policy</h3>
 * There is no automatic retry of any request. In particular a POST whose outcome is unknown (timeout, dropped
 * connection, 5xx) is not repeated: the date is listed again by marker and the result is decided from what the
 * server really holds, so a create that actually succeeded is adopted instead of duplicated.
 */
@Component
public class IntervalsWorkoutPublisher {

    private static final Logger log = LoggerFactory.getLogger(IntervalsWorkoutPublisher.class);

    /** Marker used by the legacy PowerShell publisher in the event description (command id follows it). */
    static final String LEGACY_MARKER = "[RunningAI-Control]";
    static final String EXTERNAL_ID_PREFIX = "runningai:workout:v1:";
    static final String EVENT_NAME = "RunningAI workout";
    static final String EVENT_TYPE = "Run";

    private final IntervalsWorkoutClient client;
    private final IntervalsProperties properties;

    public IntervalsWorkoutPublisher(IntervalsWorkoutClient client, IntervalsProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public IntervalsPublishResult publish(LocalDate scheduledDate, RenderedIntervalsWorkout workout) {
        String desired = workout == null ? null : workout.workoutText();
        if (scheduledDate == null) {
            throw new IllegalArgumentException("scheduledDate is required");
        }
        if (desired == null || desired.isBlank()) {
            throw new IntervalsException(Reason.EMPTY_WORKOUT, null, "The rendered workout is empty; nothing to publish");
        }
        String externalId = externalId(scheduledDate);
        IntervalsEventDraft draft = new IntervalsEventDraft(scheduledDate, EVENT_NAME, EVENT_TYPE, desired, externalId);

        Lookup lookup = lookup(scheduledDate, externalId);
        IntervalsPublishResult result;
        if (lookup.owned().size() > 1) {
            throw duplicate(scheduledDate, lookup.owned().size());
        } else if (lookup.owned().size() == 1) {
            IntervalsEvent existing = lookup.owned().get(0);
            if (matches(existing, desired, externalId)) {
                result = new IntervalsPublishResult(IntervalsPublishOperation.NO_CHANGE, existing.id(), true, scheduledDate);
            } else {
                IntervalsEvent updated = client.update(existing.id(), draft);
                verifyReadback(updated.id(), scheduledDate, desired, externalId);
                result = new IntervalsPublishResult(IntervalsPublishOperation.UPDATED, updated.id(), true, scheduledDate);
            }
        } else if (!lookup.foreign().isEmpty()) {
            throw new IntervalsException(Reason.UNMANAGED_WORKOUT_CONFLICT, null,
                    "The date " + scheduledDate + " already holds a workout that RunningAI does not own; nothing was created or changed");
        } else {
            IntervalsEvent created = createWithoutBlindRetry(draft, externalId);
            verifyReadback(created.id(), scheduledDate, desired, externalId);
            result = new IntervalsPublishResult(IntervalsPublishOperation.CREATED, created.id(), true, scheduledDate);
        }
        log.info("Intervals workout publish: date={} operation={} verified={}", scheduledDate, result.operation(), result.verified());
        return result;
    }

    /** The deterministic ownership marker (Intervals {@code external_id}) of the workout on a date. */
    String externalId(LocalDate date) {
        return EXTERNAL_ID_PREFIX + properties.athleteId() + ":" + date;
    }

    private IntervalsEvent createWithoutBlindRetry(IntervalsEventDraft draft, String externalId) {
        try {
            return client.create(draft);
        } catch (IntervalsException e) {
            if (!e.isOutcomeUnknown()) {
                throw e;
            }
            // The POST may have been applied. Never send it again: ask the server what it holds.
            log.warn("Intervals create outcome unknown (reason={}); checking by marker instead of retrying", e.getReason());
            Lookup recovered;
            try {
                recovered = lookup(draft.scheduledDate(), externalId);
            } catch (IntervalsException lookupFailure) {
                lookupFailure.addSuppressed(e);
                throw lookupFailure;
            }
            if (recovered.owned().size() > 1) {
                throw duplicate(draft.scheduledDate(), recovered.owned().size());
            }
            if (recovered.owned().size() == 1) {
                log.info("Intervals create had in fact succeeded; adopting the existing event for {}", draft.scheduledDate());
                return recovered.owned().get(0);
            }
            throw e;     // nothing was created; the caller may run publish again, which looks up first
        }
    }

    private void verifyReadback(String eventId, LocalDate date, String desired, String externalId) {
        IntervalsEvent remote = client.get(eventId);
        List<String> differences = new ArrayList<>();
        if (!eventId.equals(remote.id())) {
            differences.add("id");
        }
        if (!externalId.equals(remote.externalId())) {
            differences.add("marker");
        }
        if (!date.equals(remote.scheduledDate())) {
            differences.add("date");
        }
        if (remote.description() == null || !normalize(desired).equals(normalize(remote.description()))) {
            differences.add("workout");
        }
        if (!differences.isEmpty()) {
            throw new IntervalsException(Reason.READBACK_MISMATCH, null, eventId,
                    "Intervals readback differs from the published workout in: " + String.join(", ", differences), null);
        }
    }

    private Lookup lookup(LocalDate date, String externalId) {
        List<IntervalsEvent> owned = new ArrayList<>();
        List<IntervalsEvent> foreign = new ArrayList<>();
        for (IntervalsEvent event : client.listWorkouts(date)) {
            if (!date.equals(event.scheduledDate())) {
                continue;      // defensive: only the requested day counts
            }
            if (externalId.equals(event.externalId()) || isLegacyOwned(event)) {
                owned.add(event);
            } else {
                foreign.add(event);
            }
        }
        return new Lookup(owned, foreign);
    }

    private static boolean isLegacyOwned(IntervalsEvent event) {
        return (event.externalId() == null || event.externalId().isBlank())
                && event.description() != null && event.description().contains(LEGACY_MARKER);
    }

    /** NO_CHANGE needs a RunningAI-marked event with equal text. A legacy-marked event is always updated once, to take over the new marker. */
    private static boolean matches(IntervalsEvent existing, String desired, String externalId) {
        if (existing.description() == null) {
            return false;
        }
        if (externalId.equals(existing.externalId())) {
            return normalize(existing.description()).equals(normalize(desired));
        }
        return false;     // a legacy event always needs the update that gives it the new marker
    }

    /**
     * Minimal normalisation only: newline representation and trailing newlines. Nothing else (no trimming of
     * lines, no case folding) so that a real rendering change is never hidden.
     */
    static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        int end = unified.length();
        while (end > 0 && unified.charAt(end - 1) == '\n') {
            end--;
        }
        return unified.substring(0, end);
    }

    private static IntervalsException duplicate(LocalDate date, int count) {
        return new IntervalsException(Reason.DUPLICATE_OWNED_WORKOUT, null,
                count + " RunningAI-owned workouts exist on " + date + "; nothing was created, changed or deleted");
    }

    private record Lookup(List<IntervalsEvent> owned, List<IntervalsEvent> foreign) {
    }
}
