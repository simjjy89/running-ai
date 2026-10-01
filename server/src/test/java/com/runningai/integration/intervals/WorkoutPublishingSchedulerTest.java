package com.runningai.integration.intervals;

import com.runningai.integration.intervals.IntervalsException.Reason;
import com.runningai.training.CandidateTrainingType;
import com.runningai.training.StructuredWorkoutMapper;
import com.runningai.training.WorkoutIntensityTargetService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The tick logic with the application service mocked: no network, no Spring context, no real clock. */
class WorkoutPublishingSchedulerTest {

    /** 2026-10-01 16:30 UTC = 2026-10-02 01:30 in Asia/Seoul: the two zones disagree on the date. */
    private static final Clock BOUNDARY = Clock.fixed(Instant.parse("2026-10-01T16:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate SEOUL_DATE = LocalDate.of(2026, 10, 2);
    private static final LocalDate UTC_DATE = LocalDate.of(2026, 10, 1);

    private final WorkoutPublishApplicationService service = mock(WorkoutPublishApplicationService.class);

    private static WorkoutPublishProperties properties(boolean master, String zone) {
        return new WorkoutPublishProperties(master,
                new WorkoutPublishProperties.Scheduler(true, WorkoutPublishProperties.DEFAULT_CRON, ZoneId.of(zone)));
    }

    private WorkoutPublishingScheduler scheduler(String zone) {
        return new WorkoutPublishingScheduler(service, properties(true, zone), BOUNDARY);
    }

    private static WorkoutPublishResponse response(IntervalsPublishOperation operation, LocalDate date) {
        return new WorkoutPublishResponse(date, operation, true, CandidateTrainingType.EASY, 3);
    }

    // ---- date / zone ------------------------------------------------------------------------------------------------

    @Test
    void publishesTodayInTheConfiguredZoneNotTheUtcOrServerDate() {
        when(service.publish(any())).thenReturn(response(IntervalsPublishOperation.CREATED, SEOUL_DATE));

        scheduler("Asia/Seoul").runScheduledPublish();

        verify(service, times(1)).publish(SEOUL_DATE);
        verify(service, never()).publish(UTC_DATE);
    }

    @Test
    void theZoneComesFromConfigurationSoAnotherZoneGivesAnotherDate() {
        when(service.publish(any())).thenReturn(response(IntervalsPublishOperation.CREATED, UTC_DATE));

        scheduler("UTC").runScheduledPublish();

        verify(service, times(1)).publish(UTC_DATE);
    }

    // ---- only the application service is used ------------------------------------------------------------------------

    @Test
    void dependsOnTheApplicationServiceAndNotOnAnyPipelineStage() {
        Constructor<?> constructor = WorkoutPublishingScheduler.class.getConstructors()[0];

        List<Class<?>> dependencies = Arrays.asList(constructor.getParameterTypes());

        assertThat(dependencies).containsExactlyInAnyOrder(WorkoutPublishApplicationService.class,
                WorkoutPublishProperties.class, Clock.class);
        assertThat(dependencies).doesNotContain(StructuredWorkoutMapper.class, IntervalsWorkoutRenderer.class,
                IntervalsWorkoutPublisher.class, IntervalsWorkoutClient.class, WorkoutIntensityTargetService.class);
    }

    // ---- results -------------------------------------------------------------------------------------------------------

    @Test
    void createdUpdatedAndNoChangeAreAllNormalCompletions() {
        for (IntervalsPublishOperation operation : IntervalsPublishOperation.values()) {
            when(service.publish(SEOUL_DATE)).thenReturn(response(operation, SEOUL_DATE));

            assertThatCode(() -> scheduler("Asia/Seoul").runScheduledPublish()).doesNotThrowAnyException();
        }

        verify(service, times(IntervalsPublishOperation.values().length)).publish(SEOUL_DATE);
    }

    @Test
    void noChangeAfterAManualPublishIsNotAnError() {
        when(service.publish(SEOUL_DATE)).thenReturn(response(IntervalsPublishOperation.NO_CHANGE, SEOUL_DATE));

        assertThatCode(() -> scheduler("Asia/Seoul").runScheduledPublish()).doesNotThrowAnyException();
        verify(service, times(1)).publish(SEOUL_DATE);          // exactly one call: no retry, no second look
    }

    // ---- failures end the tick safely, never retry ---------------------------------------------------------------------------

    @Test
    void everyFailureKindEndsTheTickWithoutRetryAndWithoutEscaping() {
        List<RuntimeException> failures = List.of(
                new WorkoutPublishDisabledException(),
                new WorkoutPublishAlreadyRunningException(SEOUL_DATE),
                new IntervalsException(Reason.UPSTREAM_ERROR, 500, "Intervals.icu responded 500"),
                new IntervalsException(Reason.EMPTY_WORKOUT, null, "empty"),
                new IllegalStateException("boom"));

        for (RuntimeException failure : failures) {
            WorkoutPublishApplicationService failing = mock(WorkoutPublishApplicationService.class);
            when(failing.publish(any())).thenThrow(failure);

            assertThatCode(() -> new WorkoutPublishingScheduler(failing, properties(true, "Asia/Seoul"), BOUNDARY)
                    .runScheduledPublish()).as(failure.getClass().getSimpleName()).doesNotThrowAnyException();
            verify(failing, times(1)).publish(SEOUL_DATE);
        }
    }

    @Test
    void aFailedTickDoesNotPreventTheNextOne() {
        when(service.publish(SEOUL_DATE))
                .thenThrow(new IntervalsException(Reason.RATE_LIMITED, 429, "Intervals.icu responded 429"))
                .thenReturn(response(IntervalsPublishOperation.CREATED, SEOUL_DATE));
        WorkoutPublishingScheduler scheduler = scheduler("Asia/Seoul");

        scheduler.runScheduledPublish();
        scheduler.runScheduledPublish();

        verify(service, times(2)).publish(SEOUL_DATE);
    }

    // ---- the master switch is never bypassed -----------------------------------------------------------------------------

    @Test
    void schedulerOnMasterOffIsRefusedByTheRealServiceBeforeAnyWork() {
        WorkoutIntensityTargetService targetService = mock(WorkoutIntensityTargetService.class);
        IntervalsWorkoutPublisher publisher = mock(IntervalsWorkoutPublisher.class);
        WorkoutPublishProperties masterOff = properties(false, "Asia/Seoul");
        WorkoutPublishApplicationService realService = new WorkoutPublishApplicationService(masterOff, targetService,
                new StructuredWorkoutMapper(), new IntervalsWorkoutRenderer(), publisher);

        assertThatCode(() -> new WorkoutPublishingScheduler(realService, masterOff, BOUNDARY).runScheduledPublish())
                .doesNotThrowAnyException();

        verifyNoInteractions(targetService, publisher);
    }
}
