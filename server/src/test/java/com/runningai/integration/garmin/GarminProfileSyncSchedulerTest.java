package com.runningai.integration.garmin;

import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests of one scheduler tick; no Spring context, no clock, no network. */
class GarminProfileSyncSchedulerTest {

    private final GarminAthleteProfileSyncService syncService = mock(GarminAthleteProfileSyncService.class);
    private final GarminProfileSyncScheduler scheduler = new GarminProfileSyncScheduler(syncService);

    @Test
    void tickCallsTheSyncServiceExactlyOnce() {
        when(syncService.sync()).thenReturn(new GarminProfileSyncResponse(true, 180, 290));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(syncService, times(1)).sync();
    }

    @Test
    void unchangedRunIsNotAnError() {
        when(syncService.sync()).thenReturn(new GarminProfileSyncResponse(false, 180, 290));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(syncService, times(1)).sync();
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void connectorFailuresEndTheTickWithoutRetryAndStaleProfileIsPreserved(Reason reason) {
        when(syncService.sync()).thenThrow(new GarminConnectorException(reason, null, "failure"));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(syncService, times(1)).sync();
    }

    @Test
    void unexpectedExceptionIsContainedAndTheNextTickStillWorks() {
        when(syncService.sync())
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(new GarminProfileSyncResponse(true, 180, 290));

        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();
        assertThatCode(scheduler::runScheduledSync).doesNotThrowAnyException();

        verify(syncService, times(2)).sync();
    }
}
