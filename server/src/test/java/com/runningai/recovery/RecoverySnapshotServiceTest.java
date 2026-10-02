package com.runningai.recovery;

import com.runningai.athlete.AthleteService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Persistence and idempotency of daily recovery snapshots (H2 + Flyway). Synthetic values only. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecoverySnapshotServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 2);

    @Autowired
    private RecoverySnapshotService service;

    @Autowired
    private RecoveryRepository repository;

    @Autowired
    private AthleteService athleteService;

    @Autowired
    private EntityManager entityManager;

    private static RecoveryDailyValues full() {
        return new RecoveryDailyValues(52.0, 48.5, "BALANCED", 25200, 84, 52, 81, 40, 58, 32, 29, 88);
    }

    @Test
    void firstSyncInsertsEveryMetric() {
        RecoveryUpsertResult result = service.upsert(DAY, full());
        entityManager.flush();
        entityManager.clear();

        assertThat(result.updated()).isTrue();
        RecoverySnapshot stored = repository.findByAthleteIdAndRecoveryDate(athleteService.getDefaultAthlete().getId(), DAY)
                .orElseThrow();
        assertThat(stored.values()).isEqualTo(full());
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
    }

    @Test
    void reSyncingTheSameDataIsANoOp() {
        service.upsert(DAY, full());
        entityManager.flush();
        Instant firstUpdate = repository.findAll().get(0).getUpdatedAt();

        RecoveryUpsertResult again = service.upsert(DAY, full());
        entityManager.flush();

        assertThat(again.updated()).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().get(0).getUpdatedAt()).isEqualTo(firstUpdate);
    }

    @Test
    void aChangedValueUpdatesTheSameRowInPlace() {
        service.upsert(DAY, full());
        RecoveryDailyValues laterInTheDay = new RecoveryDailyValues(null, null, null, null, null, null,
                81, 22, 58, 59, 35, 92);

        RecoveryUpsertResult result = service.upsert(DAY, laterInTheDay);

        assertThat(result.updated()).isTrue();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(result.values().bodyBatteryLowest()).isEqualTo(22);
        assertThat(result.values().stressAverage()).isEqualTo(35);
    }

    @Test
    void aMetricMissingFromALaterSyncNeverClearsAStoredValue() {
        service.upsert(DAY, full());

        RecoveryUpsertResult result = service.upsert(DAY, new RecoveryDailyValues(null, null, null, null, null,
                53, null, null, null, null, null, null));

        assertThat(result.values().hrvLastNightAvgMs()).isEqualTo(52.0);
        assertThat(result.values().sleepDurationSeconds()).isEqualTo(25200);
        assertThat(result.values().restingHeartRateBpm()).isEqualTo(53);
    }

    @Test
    void aDayWithoutAnyValueCreatesNoRow() {
        RecoveryUpsertResult result = service.upsert(DAY, RecoveryDailyValues.EMPTY);

        assertThat(result.updated()).isFalse();
        assertThat(result.values()).isEqualTo(RecoveryDailyValues.EMPTY);
        assertThat(repository.count()).isZero();
    }

    @Test
    void differentDaysAreSeparateRowsAndAreReturnedOldestFirst() {
        service.upsert(DAY, full());
        service.upsert(DAY.minusDays(1), full());
        service.upsert(DAY.minusDays(10), full());

        assertThat(service.between(DAY.minusDays(2), DAY))
                .extracting(RecoverySnapshot::getRecoveryDate)
                .containsExactly(DAY.minusDays(1), DAY);
    }

    @Test
    void baselineServiceReadsStoredSnapshots() {
        for (int i = 1; i <= 7; i++) {
            service.upsert(DAY.minusDays(i), full());
        }
        service.upsert(DAY, new RecoveryDailyValues(41.6, null, "UNBALANCED", null, null, null, null, null, null, null, null, null));

        RecoveryBaselines baselines = new RecoveryBaselineService(service).baselines(DAY);

        RecoveryMetricBaseline hrv = baselines.metric(RecoveryMetric.HRV_LAST_NIGHT_AVG_MS).orElseThrow();
        assertThat(hrv.differencePercent()).isEqualTo(-20.0);
        assertThat(baselines.dayOf(hrv).hrvStatus()).isEqualTo("UNBALANCED");
        // RHR has no value today, so its latest reading (yesterday) is reported with its age
        assertThat(baselines.metric(RecoveryMetric.RESTING_HEART_RATE_BPM).orElseThrow().ageDays()).isEqualTo(1);
    }
}
