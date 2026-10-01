package com.runningai.integration.intervals;

import com.runningai.training.CandidateTrainingType;
import com.runningai.training.HeartRateTarget;
import com.runningai.training.IntensityClass;
import com.runningai.training.PrimaryTargetType;
import com.runningai.training.SegmentType;
import com.runningai.training.StructuredWorkoutMapper;
import com.runningai.training.TargetAvailability;
import com.runningai.training.TargetedWorkoutPrescription;
import com.runningai.training.TargetedWorkoutSegment;
import com.runningai.training.WorkoutIntensityTargetService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration of the verified pipeline behind the manual trigger. The prescription source is a mock; the mapper,
 * renderer and publisher are the real classes over a stateful fake Intervals.icu, so no network is involved.
 */
class WorkoutPublishApplicationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 2);
    private static final LocalDate OTHER_DATE = LocalDate.of(2026, 10, 3);
    private static final HeartRateTarget HR = new HeartRateTarget(65, 78, 104, 125);

    private WorkoutIntensityTargetService targetService;
    private FakeIntervalsWorkoutClient client;
    private final StructuredWorkoutMapper mapper = new StructuredWorkoutMapper();
    private final IntervalsWorkoutRenderer renderer = new IntervalsWorkoutRenderer();
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        targetService = mock(WorkoutIntensityTargetService.class);
        client = new FakeIntervalsWorkoutClient();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private WorkoutPublishApplicationService service(boolean enabled) {
        IntervalsProperties intervals = new IntervalsProperties("https://intervals.test", "test-athlete", "test-api-key",
                Duration.ofSeconds(3), Duration.ofSeconds(15));
        return new WorkoutPublishApplicationService(new WorkoutPublishProperties(enabled), targetService, mapper, renderer,
                new IntervalsWorkoutPublisher(client, intervals));
    }

    private static TargetedWorkoutPrescription prescription(LocalDate date, int mainMinutes) {
        List<TargetedWorkoutSegment> segments = List.of(
                new TargetedWorkoutSegment(SegmentType.WARM_UP, 5, IntensityClass.VERY_EASY, "warm-up",
                        PrimaryTargetType.HEART_RATE, null, HR, null),
                new TargetedWorkoutSegment(SegmentType.MAIN, mainMinutes, IntensityClass.EASY, "main",
                        PrimaryTargetType.HEART_RATE, null, HR, null),
                new TargetedWorkoutSegment(SegmentType.COOL_DOWN, 2, IntensityClass.VERY_EASY, "cool-down",
                        PrimaryTargetType.HEART_RATE, null, HR, null));
        return new TargetedWorkoutPrescription(date, CandidateTrainingType.EASY, 7 + mainMinutes, segments,
                TargetAvailability.FULL, null, null);
    }

    private void prescribe(LocalDate date, int mainMinutes) {
        when(targetService.targetedPrescribe(date)).thenReturn(prescription(date, mainMinutes));
    }

    // ---- switch ---------------------------------------------------------------------------------------

    @Test
    void disabledFailsBeforeAnyWorkIsDone() {
        assertThatThrownBy(() -> service(false).publish(DATE)).isInstanceOf(WorkoutPublishDisabledException.class);

        verifyNoInteractions(targetService);
        assertThat(client.listCalls).isZero();
        assertThat(client.writeCalls()).isZero();
    }

    @Test
    void nullDateIsRejected() {
        assertThatThrownBy(() -> service(true).publish(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- CREATED / NO_CHANGE / UPDATED (idempotency stays the publisher's) --------------------------------

    @Test
    void createsThenNoChangeThenUpdatesTheSameEvent() {
        WorkoutPublishApplicationService service = service(true);
        prescribe(DATE, 3);

        WorkoutPublishResponse first = service.publish(DATE);
        assertThat(first.operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(first.verified()).isTrue();
        assertThat(first.date()).isEqualTo(DATE);
        assertThat(first.intent()).isEqualTo(CandidateTrainingType.EASY);
        assertThat(first.stepCount()).isEqualTo(3);
        assertThat(client.events).hasSize(1);
        String remoteId = client.events.keySet().iterator().next();

        WorkoutPublishResponse repeated = service.publish(DATE);
        assertThat(repeated.operation()).isEqualTo(IntervalsPublishOperation.NO_CHANGE);
        assertThat(client.writeCalls()).isEqualTo(1);

        prescribe(DATE, 4);
        WorkoutPublishResponse changed = service.publish(DATE);
        assertThat(changed.operation()).isEqualTo(IntervalsPublishOperation.UPDATED);
        assertThat(changed.verified()).isTrue();
        assertThat(client.events).hasSize(1).containsKey(remoteId);
        assertThat(client.createCalls).isEqualTo(1);
        assertThat(client.updateCalls).isEqualTo(1);
    }

    // ---- orchestration: reuse only, nothing recomputed -------------------------------------------------------

    @Test
    void publishesExactlyWhatTheExistingMapperAndRendererProduce() {
        TargetedWorkoutPrescription prescription = prescription(DATE, 3);
        when(targetService.targetedPrescribe(DATE)).thenReturn(prescription);

        service(true).publish(DATE);

        String expected = renderer.render(mapper.map(prescription)).workoutText();
        assertThat(expected).contains("65-78% LTHR hr=1s");
        assertThat(client.events.values()).singleElement()
                .satisfies(event -> assertThat(event.description()).isEqualTo(expected));
        verify(targetService).targetedPrescribe(DATE);
    }

    @Test
    void usesTheMapperRendererAndPublisherBeans() {
        StructuredWorkoutMapper mockMapper = mock(StructuredWorkoutMapper.class);
        IntervalsWorkoutRenderer mockRenderer = mock(IntervalsWorkoutRenderer.class);
        IntervalsWorkoutPublisher mockPublisher = mock(IntervalsWorkoutPublisher.class);
        TargetedWorkoutPrescription prescription = prescription(DATE, 3);
        var structured = mapper.map(prescription);
        var rendered = new RenderedIntervalsWorkout("- Main 3m");
        when(targetService.targetedPrescribe(DATE)).thenReturn(prescription);
        when(mockMapper.map(prescription)).thenReturn(structured);
        when(mockRenderer.render(structured)).thenReturn(rendered);
        when(mockPublisher.publish(DATE, rendered))
                .thenReturn(new IntervalsPublishResult(IntervalsPublishOperation.CREATED, "remote", true, DATE));

        WorkoutPublishResponse response = new WorkoutPublishApplicationService(new WorkoutPublishProperties(true),
                targetService, mockMapper, mockRenderer, mockPublisher).publish(DATE);

        assertThat(response.operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(response.stepCount()).isEqualTo(3);
        verify(mockMapper).map(prescription);
        verify(mockRenderer).render(structured);
        verify(mockPublisher).publish(DATE, rendered);
    }

    // ---- errors are propagated, never hidden --------------------------------------------------------------------

    @Test
    void publisherFailureIsPropagatedAndNothingIsReportedAsSuccess() {
        prescribe(DATE, 3);
        client.storedDescription = text -> text + "\nsomething else";       // readback will differ

        assertThatThrownBy(() -> service(true).publish(DATE))
                .isInstanceOfSatisfying(IntervalsException.class,
                        e -> assertThat(e.getReason()).isEqualTo(IntervalsException.Reason.READBACK_MISMATCH));
    }

    @Test
    void prescriptionFailureIsPropagatedWithoutTouchingIntervals() {
        when(targetService.targetedPrescribe(DATE)).thenThrow(new IllegalStateException("no profile"));

        assertThatThrownBy(() -> service(true).publish(DATE)).isInstanceOf(IllegalStateException.class);
        assertThat(client.listCalls).isZero();
    }

    // ---- single-flight per date ---------------------------------------------------------------------------------------

    /** Makes the prescription of {@code blockedDate} wait until released; any other date returns at once. */
    private CountDownLatch blockOn(LocalDate blockedDate, CountDownLatch entered) {
        CountDownLatch release = new CountDownLatch(1);
        when(targetService.targetedPrescribe(any(LocalDate.class))).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(0);
            if (date.equals(blockedDate)) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release");
                }
            }
            return prescription(date, 3);
        });
        return release;
    }

    @Test
    void sameDateWhileRunningIsRejectedAndAllowedAgainAfterwards() throws Exception {
        WorkoutPublishApplicationService service = service(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = blockOn(DATE, entered);

        Future<WorkoutPublishResponse> first = executor.submit(() -> service.publish(DATE));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.publish(DATE)).isInstanceOf(WorkoutPublishAlreadyRunningException.class);
        assertThat(client.writeCalls()).isZero();           // the rejected request did nothing

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).operation()).isEqualTo(IntervalsPublishOperation.CREATED);

        assertThat(service.publish(DATE).operation()).isEqualTo(IntervalsPublishOperation.NO_CHANGE);
        assertThat(client.createCalls).isEqualTo(1);
    }

    @Test
    void differentDatesDoNotBlockEachOther() throws Exception {
        WorkoutPublishApplicationService service = service(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = blockOn(DATE, entered);

        Future<WorkoutPublishResponse> first = executor.submit(() -> service.publish(DATE));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(service.publish(OTHER_DATE).operation()).isEqualTo(IntervalsPublishOperation.CREATED);

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).operation()).isEqualTo(IntervalsPublishOperation.CREATED);
        assertThat(client.events).hasSize(2);
    }

    @Test
    void guardIsReleasedAfterAFailure() {
        WorkoutPublishApplicationService service = service(true);
        when(targetService.targetedPrescribe(DATE))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(prescription(DATE, 3));

        assertThatThrownBy(() -> service.publish(DATE)).isInstanceOf(IllegalStateException.class);

        assertThat(service.publish(DATE).operation()).isEqualTo(IntervalsPublishOperation.CREATED);
    }

    @Test
    void guardIsReleasedAfterAPublisherFailure() {
        WorkoutPublishApplicationService service = service(true);
        prescribe(DATE, 3);
        client.listFailure = new IntervalsException(IntervalsException.Reason.UPSTREAM_ERROR, 500, "Intervals.icu responded 500");

        assertThatThrownBy(() -> service.publish(DATE)).isInstanceOf(IntervalsException.class);

        client.listFailure = null;
        assertThat(service.publish(DATE).operation()).isEqualTo(IntervalsPublishOperation.CREATED);
    }

    @Test
    void disabledDoesNotTakeTheGuard() {
        WorkoutPublishApplicationService disabled = service(false);
        assertThatThrownBy(() -> disabled.publish(DATE)).isInstanceOf(WorkoutPublishDisabledException.class);
        assertThatThrownBy(() -> disabled.publish(DATE)).isInstanceOf(WorkoutPublishDisabledException.class);
        verify(targetService, never()).targetedPrescribe(any());
    }
}
