package com.runningai.integration.garmin;

import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests of one scheduler tick; no Spring context, no clock, no network. */
class GarminSyncSchedulerTest {

    private final GarminSyncOperationService operationService = mock(GarminSyncOperationService.class);
    private final GarminSyncScheduler scheduler = new GarminSyncScheduler(operationService);

    @Test
    void tickCallsTheSharedOperationServiceExactlyOnce() {
        when(operationService.runSync())
                .thenReturn(new GarminSyncResponse(5, 1, 2, 2, 0, 1, true, Instant.now(), Instant.now()));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(operationService, times(1)).runSync();
    }

    @Test
    void alreadyRunningIsASilentSkip() {
        when(operationService.runSync()).thenThrow(new GarminSyncAlreadyRunningException());

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(operationService, times(1)).runSync();
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void connectorFailuresEndTheTickWithoutRetry(Reason reason) {
        when(operationService.runSync()).thenThrow(new GarminConnectorException(reason, null, "failure"));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(operationService, times(1)).runSync();   // rate limit, auth, unavailable, ...: one attempt only
    }

    @Test
    void incompleteWindowEndsTheTickWithoutRetry() {
        when(operationService.runSync()).thenThrow(new GarminIncrementalSyncException("window incomplete"));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(operationService, times(1)).runSync();
    }

    @Test
    void unexpectedExceptionIsContainedAndTheNextTickStillWorks() {
        when(operationService.runSync())
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(new GarminSyncResponse(0, 0, 0, 0, 0, 1, false, null, null));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();
        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(operationService, times(2)).runSync();   // one call per tick, none repeated within a tick
    }
}
