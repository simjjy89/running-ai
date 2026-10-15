package com.runningai.integration.garmin;

import com.runningai.activity.ActivityRawRepository;
import com.runningai.activity.ActivityRepository;
import com.runningai.athlete.AthleteService;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.activity;
import static com.runningai.integration.garmin.GarminIncrementalSyncFixtures.malformedNoStartTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Phase 8B: the two states the Phase 8A ledger could not express, against the real schema.
 * <p>
 * An attempt now announces itself before the connector is called, so a process killed mid-sync
 * leaves a {@code STARTED} row instead of leaving the ledger describing the run BEFORE it. And a
 * run that completes with unmappable items keeps its own streak, because repeating it holds the
 * checkpoint back and re-reads the same window - a stall the failure counter could never see,
 * since every one of those attempts completed.
 * <p>
 * What must not change is pinned too: an interruption is not a failure, and the manual sync path
 * is never gated by the breaker.
 */
@SpringBootTest
@ActiveProfiles("test")
class GarminSyncAttemptLifecycleTest {

    @Autowired
    private GarminSyncOperationService operationService;

    @Autowired
    private GarminSyncAttemptService attemptService;

    @Autowired
    private GarminSyncAttemptRepository attemptRepository;

    @Autowired
    private GarminSyncAttemptReconciler reconciler;

    @Autowired
    private GarminSyncStatusService statusService;

    @Autowired
    private GarminSyncStateRepository syncStateRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ActivityRawRepository activityRawRepository;

    @Autowired
    private AthleteService athleteService;

    @MockitoBean
    private GarminActivitySource activitySource;

    private Long athleteId;

    @BeforeEach
    void setUp() {
        athleteId = athleteService.getDefaultAthlete().getId();
    }

    @AfterEach
    void cleanUp() {
        activityRawRepository.deleteAll();
        activityRepository.deleteAll();
        syncStateRepository.deleteAll();
        attemptRepository.deleteAll();
    }

    private GarminSyncAttempt attempt() {
        return attemptRepository.findByAthleteId(athleteId).orElseThrow();
    }

    /**
     * What a killed JVM leaves behind: the STARTED row written before the connector call, never
     * overwritten. The reconciler is what the next startup would run.
     */
    @Test
    void aStartedAttemptLeftByADeadProcessBecomesInterruptedAtStartup() {
        attemptService.recordStarted(athleteId, java.time.Instant.parse("2026-10-15T01:00:00Z"));
        assertThat(attempt().getOutcome()).isEqualTo(GarminSyncAttemptOutcome.STARTED);

        reconciler.reconcileInterruptedAttempts();

        GarminSyncAttempt reconciled = attempt();
        assertThat(reconciled.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.INTERRUPTED);
        assertThat(reconciled.getFailureCode()).isEqualTo(GarminSyncAttemptReconciler.INTERRUPTED_CODE);
    }

    /**
     * An interruption is this machine dying, not Garmin refusing anything. Counting it as a failure
     * would make every watchdog restart a step towards stopping automatic collection.
     */
    @Test
    void anInterruptionNeverCountsAsAFailure() {
        attemptService.recordStarted(athleteId, java.time.Instant.parse("2026-10-15T01:00:00Z"));

        reconciler.reconcileInterruptedAttempts();

        assertThat(attempt().getConsecutiveFailureCount()).isZero();
        assertThat(attempt().getLastFailureCode()).isNull();
    }

    @Test
    void reconcilingLeavesAFinishedAttemptAlone() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00")));
        operationService.runSync();

        reconciler.reconcileInterruptedAttempts();

        assertThat(attempt().getOutcome()).isEqualTo(GarminSyncAttemptOutcome.SUCCESS);
    }

    /** A completed sync must leave no trace of the STARTED row it passed through. */
    @Test
    void aCompletedSyncOverwritesItsOwnStartedRow() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00")));

        operationService.runSync();

        assertThat(attemptRepository.findAll()).hasSize(1);
        assertThat(attempt().getOutcome()).isEqualTo(GarminSyncAttemptOutcome.SUCCESS);
        assertThat(attempt().getCreated()).isEqualTo(1);
    }

    @Test
    void aFailedSyncOverwritesItsOwnStartedRow() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(Reason.AUTH_REQUIRED, 401, "expired"));

        assertThatThrownBy(() -> operationService.runSync()).isInstanceOf(GarminConnectorException.class);

        assertThat(attempt().getOutcome()).isEqualTo(GarminSyncAttemptOutcome.FAILED);
        assertThat(attempt().getFailureCode()).isEqualTo("GARMIN_AUTH_REQUIRED");
    }

    /**
     * The stall the failure counter cannot see: every one of these runs COMPLETED, so the failure
     * streak stays 0 while the checkpoint never moves and the same window is read again.
     */
    @Test
    void repeatedPartialRunsAccumulateTheirOwnStreakWhileTheFailureStreakStaysZero() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00"), malformedNoStartTime(1002L)));

        operationService.runSync();
        operationService.runSync();
        operationService.runSync();

        GarminSyncAttempt attempt = attempt();
        assertThat(attempt.getOutcome()).isEqualTo(GarminSyncAttemptOutcome.PARTIAL);
        assertThat(attempt.getConsecutivePartialCount()).isEqualTo(3);
        assertThat(attempt.getConsecutiveFailureCount()).isZero();
        assertThat(attempt.isCheckpointAdvanced()).isFalse();
    }

    /**
     * The invariant the whole breaker design rests on: it governs the scheduler, never the human.
     * Here the ledger is in the worst state it can be in - well past the auth threshold - and the
     * manual path still runs the sync, because that request IS the intervention being waited for.
     */
    @Test
    void theManualSyncPathIsNeverBlockedHoweverBadTheLedgerLooks() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(Reason.AUTH_REQUIRED, 401, "expired"));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> operationService.runSync()).isInstanceOf(GarminConnectorException.class);
        }
        assertThat(attempt().getConsecutiveFailureCount()).isEqualTo(5);
        assertThat(statusService.getStatus().automaticSync().allowed()).isFalse();

        // doReturn, not when(...): re-stubbing a mock that currently throws would call it here.
        org.mockito.Mockito.doReturn(List.of(activity(1001L, "running", "2026-10-10 06:00:00")))
                .when(activitySource).fetchActivities(anyInt(), anyInt());
        operationService.runSync();

        assertThat(activityRepository.findAll()).hasSize(1);
        // ... and the completed attempt is what lifts the auth block, with no flag to clear by
        // hand. What remains is only the ordinary pacing floor between two automatic attempts,
        // which is a different statement entirely: "not yet" rather than "not until a human acts".
        GarminSyncGateDecision after = statusService.getStatus().automaticSync();
        assertThat(after.reason()).isEqualTo(GarminSyncGateReason.MINIMUM_INTERVAL);
        assertThat(after.retryAfter()).isNotNull();
    }

    /** The status endpoint has to be able to say WHY the scheduler is quiet. */
    @Test
    void theStatusReportExplainsAnAutomaticBlock() {
        when(activitySource.fetchActivities(anyInt(), anyInt()))
                .thenThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "slow down"));

        assertThatThrownBy(() -> operationService.runSync()).isInstanceOf(GarminConnectorException.class);

        GarminSyncStatusResponse status = statusService.getStatus();
        assertThat(status.automaticSync().allowed()).isFalse();
        assertThat(status.automaticSync().reason()).isEqualTo(GarminSyncGateReason.RATE_LIMITED);
        assertThat(status.automaticSync().retryAfter()).isNotNull();
        assertThat(status.lastAttempt().failureClass()).isEqualTo(GarminFailureClass.RATE_LIMIT);
    }

    /** Recording must stay unable to move the checkpoint, whichever state it writes. */
    @Test
    void recordingAStartOrAnInterruptionNeverTouchesTheCheckpoint() {
        attemptService.recordStarted(athleteId, java.time.Instant.parse("2026-10-15T01:00:00Z"));
        reconciler.reconcileInterruptedAttempts();

        assertThat(syncStateRepository.findByAthleteId(athleteId)).isEmpty();
    }
}
