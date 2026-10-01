package com.runningai.integration.intervals;

import com.runningai.integration.intervals.IntervalsException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publish orchestration against a stateful fake Intervals.icu. Workouts are fixed
 * {@link RenderedIntervalsWorkout} fixtures: rendering (pace, %LTHR, cues) is the renderer's tests' job.
 */
class IntervalsWorkoutPublisherTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final LocalDate OTHER_DATE = LocalDate.of(2026, 10, 2);
    private static final String MARKER = "runningai:workout:v1:test-athlete:2026-10-01";
    private static final RenderedIntervalsWorkout EASY = new RenderedIntervalsWorkout(
            "- Warm up 10m 60-70% LTHR hr=1s\n- Easy 30m 65-75% LTHR hr=1s\n- Cool down 5m 60-70% LTHR hr=1s");
    private static final RenderedIntervalsWorkout CHANGED = new RenderedIntervalsWorkout(
            "- Warm up 10m 60-70% LTHR hr=1s\n- Easy 35m 65-75% LTHR hr=1s\n- Cool down 5m 60-70% LTHR hr=1s");

    private FakeIntervalsWorkoutClient client;
    private IntervalsWorkoutPublisher publisher;

    @BeforeEach
    void setUp() {
        client = new FakeIntervalsWorkoutClient();
        IntervalsProperties properties = new IntervalsProperties("https://intervals.test", "test-athlete", "test-api-key",
                Duration.ofSeconds(3), Duration.ofSeconds(15));
        publisher = new IntervalsWorkoutPublisher(client, properties);
    }

    private static void assertReason(Throwable t, Reason reason) {
        assertThat(t).isInstanceOfSatisfying(IntervalsException.class, e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    // ---- CREATE / NO_CHANGE / UPDATE -----------------------------------------------------------------

    @Test
    void createsWhenNothingExistsAndVerifiesByReadback() {
        IntervalsPublishResult result = publisher.publish(DATE, EASY);

        assertThat(result.operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(result.verified()).isTrue();
        assertThat(result.scheduledDate()).isEqualTo(DATE);
        assertThat(client.createCalls).isEqualTo(1);
        assertThat(client.getCalls).isEqualTo(1);       // readback
        IntervalsEvent stored = client.events.get(result.remoteEventId());
        assertThat(stored.description()).isEqualTo(EASY.workoutText());     // sent unchanged
        assertThat(stored.externalId()).isEqualTo(MARKER);
        assertThat(stored.scheduledDate()).isEqualTo(DATE);
        assertThat(stored.type()).isEqualTo("Run");
    }

    @Test
    void secondIdenticalPublishIsNoChangeWithoutAnyWrite() {
        IntervalsPublishResult first = publisher.publish(DATE, EASY);
        IntervalsPublishResult second = publisher.publish(DATE, EASY);

        assertThat(first.operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(second.operation()).isEqualTo(IntervalsPublishOperation.NO_CHANGE);
        assertThat(second.remoteEventId()).isEqualTo(first.remoteEventId());
        assertThat(second.verified()).isTrue();
        assertThat(client.createCalls).isEqualTo(1);
        assertThat(client.updateCalls).isZero();
        assertThat(client.events).hasSize(1);
    }

    @Test
    void changedWorkoutUpdatesTheSameRemoteEvent() {
        IntervalsPublishResult first = publisher.publish(DATE, EASY);
        IntervalsPublishResult second = publisher.publish(DATE, CHANGED);
        IntervalsPublishResult third = publisher.publish(DATE, CHANGED);

        assertThat(second.operation()).isEqualTo(IntervalsPublishOperation.UPDATED);
        assertThat(second.remoteEventId()).isEqualTo(first.remoteEventId());
        assertThat(second.verified()).isTrue();
        assertThat(third.operation()).isEqualTo(IntervalsPublishOperation.NO_CHANGE);
        assertThat(client.events).hasSize(1);
        assertThat(client.createCalls).isEqualTo(1);
        assertThat(client.updateCalls).isEqualTo(1);
        assertThat(client.events.get(first.remoteEventId()).description()).isEqualTo(CHANGED.workoutText());
    }

    @Test
    void differentDatesAreIndependentWorkouts() {
        IntervalsPublishResult a = publisher.publish(DATE, EASY);
        IntervalsPublishResult b = publisher.publish(OTHER_DATE, EASY);

        assertThat(b.operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(b.remoteEventId()).isNotEqualTo(a.remoteEventId());
        assertThat(client.events.get(b.remoteEventId()).externalId()).isEqualTo("runningai:workout:v1:test-athlete:2026-10-02");
    }

    // ---- ownership -------------------------------------------------------------------------------------

    @Test
    void anotherEventOnTheSameDateIsNeverModifiedAndBlocksCreation() {
        IntervalsEvent mine = client.seed(DATE, "My own tempo run", "- 5x1km", null);

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> assertReason(t, Reason.UNMANAGED_WORKOUT_CONFLICT));

        assertThat(client.writeCalls()).isZero();
        assertThat(client.events.get(mine.id())).isEqualTo(mine);
        assertThat(client.events).hasSize(1);
    }

    @Test
    void anOwnedEventIsUpdatedWhileASiblingForeignEventIsLeftAlone() {
        IntervalsEvent foreign = client.seed(DATE, "Strength", "gym", null);
        IntervalsEvent owned = client.seed(DATE, "RunningAI workout", EASY.workoutText(), MARKER);

        IntervalsPublishResult result = publisher.publish(DATE, CHANGED);

        assertThat(result.operation()).isEqualTo(IntervalsPublishOperation.UPDATED);
        assertThat(result.remoteEventId()).isEqualTo(owned.id());
        assertThat(client.events.get(foreign.id())).isEqualTo(foreign);
    }

    @Test
    void aForeignEventWithAnotherAthletesMarkerIsNotOwned() {
        IntervalsEvent other = client.seed(DATE, "x", "- 10m", "runningai:workout:v1:someone-else:2026-10-01");

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> assertReason(t, Reason.UNMANAGED_WORKOUT_CONFLICT));
        assertThat(client.events.get(other.id())).isEqualTo(other);
    }

    @Test
    void eventsOnOtherDatesNeverMatter() {
        client.seed(OTHER_DATE, "RunningAI workout", EASY.workoutText(), "runningai:workout:v1:test-athlete:2026-10-02");

        assertThat(publisher.publish(DATE, EASY).operation()).isEqualTo(IntervalsPublishOperation.CREATED);
    }

    // ---- legacy compatibility ------------------------------------------------------------------------------

    @Test
    void aLegacyMarkedEventIsAdoptedInPlaceNotDuplicated() {
        IntervalsEvent legacy = client.seed(DATE, "Legacy workout",
                "[RunningAI-Control] command_id=cmd-test-1\n- Easy 40m", null);

        IntervalsPublishResult result = publisher.publish(DATE, EASY);

        assertThat(result.operation()).isEqualTo(IntervalsPublishOperation.UPDATED);
        assertThat(result.remoteEventId()).isEqualTo(legacy.id());
        assertThat(client.events).hasSize(1);
        assertThat(client.createCalls).isZero();
        IntervalsEvent adopted = client.events.get(legacy.id());
        assertThat(adopted.externalId()).isEqualTo(MARKER);
        assertThat(adopted.description()).isEqualTo(EASY.workoutText());

        assertThat(publisher.publish(DATE, EASY).operation()).isEqualTo(IntervalsPublishOperation.NO_CHANGE);
    }

    @Test
    void aLegacyEventAndASpringEventTogetherAreAmbiguous() {
        client.seed(DATE, "Legacy", "[RunningAI-Control] command_id=cmd-test-2\n- Easy 40m", null);
        client.seed(DATE, "RunningAI workout", EASY.workoutText(), MARKER);

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> assertReason(t, Reason.DUPLICATE_OWNED_WORKOUT));
        assertThat(client.writeCalls()).isZero();
    }

    // ---- duplicates ------------------------------------------------------------------------------------------

    @Test
    void twoOwnedEventsAbortWithoutCreatingUpdatingOrDeleting() {
        client.seed(DATE, "RunningAI workout", EASY.workoutText(), MARKER);
        client.seed(DATE, "RunningAI workout", EASY.workoutText(), MARKER);

        assertThatThrownBy(() -> publisher.publish(DATE, CHANGED)).satisfies(t -> {
            assertReason(t, Reason.DUPLICATE_OWNED_WORKOUT);
            assertThat(((IntervalsException) t).getCode()).isEqualTo("INTERVALS_DUPLICATE_OWNED_WORKOUT");
        });
        assertThat(client.writeCalls()).isZero();
        assertThat(client.events).hasSize(2);
    }

    // ---- unknown create outcome (no blind POST retry) ---------------------------------------------------------

    @Test
    void createTimeoutThatActuallySucceededIsAdoptedNotRepeated() {
        client.createFailure = new IntervalsException(Reason.TIMEOUT, null, "Intervals.icu request timed out");
        client.createFailureStillApplies = true;

        IntervalsPublishResult result = publisher.publish(DATE, EASY);

        assertThat(result.operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(result.verified()).isTrue();
        assertThat(client.createCalls).isEqualTo(1);        // no second POST
        assertThat(client.events).hasSize(1);
        assertThat(client.listCalls).isEqualTo(2);           // initial lookup + recovery lookup by marker
    }

    @Test
    void createTimeoutThatDidNotApplyFailsOnceAndALaterPublishRecovers() {
        client.createFailure = new IntervalsException(Reason.CONNECTION_FAILED, null, "Intervals.icu not reachable");

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> assertReason(t, Reason.CONNECTION_FAILED));
        assertThat(client.createCalls).isEqualTo(1);        // not retried inside the call
        assertThat(client.events).isEmpty();

        client.createFailure = null;
        assertThat(publisher.publish(DATE, EASY).operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(client.events).hasSize(1);
    }

    @Test
    void serverErrorOnCreateIsAlsoTreatedAsUnknownAndChecked() {
        client.createFailure = new IntervalsException(Reason.UPSTREAM_ERROR, 502, "Intervals.icu responded 502");
        client.createFailureStillApplies = true;

        IntervalsPublishResult result = publisher.publish(DATE, EASY);

        assertThat(result.operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(client.createCalls).isEqualTo(1);
        assertThat(client.events).hasSize(1);
    }

    @Test
    void aRejectedCreateIsDefinitiveAndNeedsNoRecoveryLookup() {
        client.createFailure = new IntervalsException(Reason.CLIENT_ERROR, 422, "Intervals.icu responded 422");

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> assertReason(t, Reason.CLIENT_ERROR));
        assertThat(client.createCalls).isEqualTo(1);
        assertThat(client.listCalls).isEqualTo(1);
    }

    @Test
    void ifTheRecoveryLookupFindsTwoOwnedEventsItAbortsWithoutAnotherWrite() {
        FakeIntervalsWorkoutClient racing = new FakeIntervalsWorkoutClient() {
            @Override
            public java.util.List<IntervalsEvent> listWorkouts(LocalDate date) {
                super.listWorkouts(date);
                return listCalls == 1 ? java.util.List.of() : java.util.List.of(
                        new IntervalsEvent("a", DATE, "RunningAI workout", "Run", "- 1", MARKER),
                        new IntervalsEvent("b", DATE, "RunningAI workout", "Run", "- 2", MARKER));
            }
        };
        racing.createFailure = new IntervalsException(Reason.TIMEOUT, null, "Intervals.icu request timed out");
        IntervalsWorkoutPublisher p = new IntervalsWorkoutPublisher(racing,
                new IntervalsProperties("https://intervals.test", "test-athlete", "k", Duration.ofSeconds(3), Duration.ofSeconds(15)));

        assertThatThrownBy(() -> p.publish(DATE, EASY)).satisfies(t -> assertReason(t, Reason.DUPLICATE_OWNED_WORKOUT));
        assertThat(racing.createCalls).isEqualTo(1);
        assertThat(racing.updateCalls).isZero();
    }

    @Test
    void ifTheRecoveryLookupItselfFailsTheOriginalCreateFailureStaysVisible() {
        client.createFailure = new IntervalsException(Reason.TIMEOUT, null, "Intervals.icu request timed out");
        client.createFailureStillApplies = true;
        client.failListAfter = 1;
        client.laterListFailure = new IntervalsException(Reason.CONNECTION_FAILED, null, "Intervals.icu not reachable");

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> {
            assertReason(t, Reason.CONNECTION_FAILED);
            assertThat(t.getSuppressed()).hasSize(1);       // the original create failure is kept
        });
        assertThat(client.createCalls).isEqualTo(1);
    }

    @Test
    void updateFailuresAreNotRetried() {
        client.seed(DATE, "RunningAI workout", EASY.workoutText(), MARKER);
        client.updateFailure = new IntervalsException(Reason.TIMEOUT, null, "Intervals.icu request timed out");

        assertThatThrownBy(() -> publisher.publish(DATE, CHANGED)).satisfies(t -> assertReason(t, Reason.TIMEOUT));
        assertThat(client.updateCalls).isEqualTo(1);
    }

    // ---- readback verification -----------------------------------------------------------------------------------

    @Test
    void createReadbackWithDifferentWorkoutTextIsAMismatchNotASuccess() {
        client.storedDescription = text -> text.replace("35m", "36m").replace("30m", "29m");

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> {
            assertReason(t, Reason.READBACK_MISMATCH);
            IntervalsException e = (IntervalsException) t;
            assertThat(e.getCode()).isEqualTo("INTERVALS_READBACK_MISMATCH");
            assertThat(e.getMessage()).contains("workout").doesNotContain("Easy");     // field names only, no workout text
            assertThat(e.getRemoteEventId()).isNotNull();
        });
        assertThat(client.createCalls).isEqualTo(1);
        assertThat(client.updateCalls).isZero();          // no automatic repair loop
    }

    @Test
    void updateReadbackMismatchIsAlsoAFailure() {
        client.seed(DATE, "RunningAI workout", EASY.workoutText(), MARKER);
        client.storedDescription = text -> text + "\n- extra";

        assertThatThrownBy(() -> publisher.publish(DATE, CHANGED)).satisfies(t -> assertReason(t, Reason.READBACK_MISMATCH));
        assertThat(client.updateCalls).isEqualTo(1);
    }

    @Test
    void aServerThatDropsTheMarkerIsDetectedByReadback() {
        client.storedExternalId = "";

        assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> {
            assertReason(t, Reason.READBACK_MISMATCH);
            assertThat(t.getMessage()).contains("marker");
        });
    }

    @Test
    void aReadbackOnAnotherDateIsAMismatch() {
        FakeIntervalsWorkoutClient shifting = new FakeIntervalsWorkoutClient() {
            @Override
            public IntervalsEvent get(String eventId) {
                IntervalsEvent e = super.get(eventId);
                return new IntervalsEvent(e.id(), e.scheduledDate().plusDays(1), e.name(), e.type(), e.description(), e.externalId());
            }
        };
        IntervalsWorkoutPublisher p = new IntervalsWorkoutPublisher(shifting,
                new IntervalsProperties("https://intervals.test", "test-athlete", "k", Duration.ofSeconds(3), Duration.ofSeconds(15)));

        assertThatThrownBy(() -> p.publish(DATE, EASY)).satisfies(t -> {
            assertReason(t, Reason.READBACK_MISMATCH);
            assertThat(t.getMessage()).contains("date");
        });
    }

    // ---- comparison / normalisation ---------------------------------------------------------------------------------

    @Test
    void newlineRepresentationAndTrailingNewlinesAreNotAChange() {
        client.seed(DATE, "RunningAI workout", EASY.workoutText().replace("\n", "\r\n") + "\r\n\r\n", MARKER);

        IntervalsPublishResult result = publisher.publish(DATE, EASY);

        assertThat(result.operation()).isEqualTo(IntervalsPublishOperation.NO_CHANGE);
        assertThat(client.writeCalls()).isZero();
    }

    @Test
    void anyRealTextDifferenceIsAnUpdateEvenWhenItIsOnlyWhitespaceCaseOrIndentation() {
        for (String altered : new String[]{
                EASY.workoutText().replace("Easy 30m", "Easy  30m"),
                EASY.workoutText().replace("Easy", "easy"),
                " " + EASY.workoutText(),
                EASY.workoutText().replace("\n- Cool", "\n -Cool").replace("-Cool", "- Cool")}) {
            client.events.clear();
            client.seed(DATE, "RunningAI workout", altered, MARKER);

            assertThat(publisher.publish(DATE, EASY).operation()).as(altered).isEqualTo(IntervalsPublishOperation.UPDATED);
        }
    }

    // ---- guards and error propagation ---------------------------------------------------------------------------------

    @Test
    void anEmptyRenderedWorkoutIsNeverPublished() {
        for (RenderedIntervalsWorkout empty : new RenderedIntervalsWorkout[]{new RenderedIntervalsWorkout(""),
                new RenderedIntervalsWorkout("  \n"), new RenderedIntervalsWorkout(null), null}) {
            assertThatThrownBy(() -> publisher.publish(DATE, empty)).satisfies(t -> assertReason(t, Reason.EMPTY_WORKOUT));
        }
        assertThat(client.listCalls).isZero();
        assertThat(client.writeCalls()).isZero();
    }

    @Test
    void lookupFailuresPropagateWithoutAnyWrite() {
        for (Reason reason : new Reason[]{Reason.AUTH_FAILED, Reason.FORBIDDEN, Reason.RATE_LIMITED, Reason.TIMEOUT, Reason.UPSTREAM_ERROR}) {
            client.listFailure = new IntervalsException(reason, null, "x");

            assertThatThrownBy(() -> publisher.publish(DATE, EASY)).satisfies(t -> assertReason(t, reason));
        }
        assertThat(client.writeCalls()).isZero();
    }

    @Test
    void theMarkerEncodesAthleteAndDateOnly() {
        assertThat(publisher.externalId(DATE)).isEqualTo(MARKER);
        assertThat(publisher.externalId(OTHER_DATE)).isEqualTo("runningai:workout:v1:test-athlete:2026-10-02");
    }

    @Test
    void normalizeOnlyTouchesNewlineStyleAndTrailingNewlines() {
        assertThat(IntervalsWorkoutPublisher.normalize("a\r\nb\r\n")).isEqualTo("a\nb");
        assertThat(IntervalsWorkoutPublisher.normalize("a\rb\n\n")).isEqualTo("a\nb");
        assertThat(IntervalsWorkoutPublisher.normalize(" a \n b ")).isEqualTo(" a \n b ");
        assertThat(IntervalsWorkoutPublisher.normalize("\n\n")).isEmpty();
    }
}
