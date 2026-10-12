package com.runningai.athlete;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Phase 7B-4: the lactate-threshold ledger against the real schema (H2 + Flyway V23). Not
 * {@code @Transactional}: transactional atomicity, the row lock and concurrency are the things
 * under test, so everything must be observed as committed (or rolled back) for real.
 */
@SpringBootTest(properties = {"running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"})
@ActiveProfiles("test")
class AthleteIntensityProfileHistoryTest {

    @Autowired private AthleteIntensityProfileService service;
    @Autowired private AthleteIntensityProfileRepository profiles;
    @Autowired private AthleteService athleteService;
    @Autowired private JdbcTemplate jdbc;

    @MockitoSpyBean private AthleteIntensityProfileHistoryRepository history;

    @AfterEach
    void cleanUp() {
        reset(history);
        jdbc.update("delete from athlete_intensity_profile_history");
        jdbc.update("delete from athlete_intensity_profile");
    }

    private Long athleteId() {
        return athleteService.getDefaultAthlete().getId();
    }

    private List<AthleteIntensityProfileHistory> ledger() {
        return history.findByAthleteIdOrderByRecordedAtDescIdDesc(athleteId());
    }

    // ---- recording and provenance ---------------------------------------------------------------

    @Test
    void aManualChangeIsAppliedAndFullyTracked() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(176, 290));

        List<AthleteIntensityProfileHistory> rows = ledger();
        assertThat(rows).hasSize(1);
        AthleteIntensityProfileHistory row = rows.get(0);
        assertThat(row.getSource()).isEqualTo(AthleteIntensityProfileHistory.Source.MANUAL);
        assertThat(row.isApplied()).isTrue();
        assertThat(row.getLactateThresholdHeartRateBpm()).isEqualTo(176);
        assertThat(row.getPreviousHeartRateBpm()).isNull(); // first value ever: no predecessor

        LactateThresholdProvenance p = service.provenance();
        assertThat(p.sourceTracked()).isTrue();
        assertThat(p.source()).isEqualTo("MANUAL");
        assertThat(p.lastAppliedChangeAt()).isEqualTo(row.getRecordedAt());
    }

    @Test
    void aGarminChangeRecordsTheVerbatimCalendarDateAndFetchTime() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(180, 300));
        Instant fetched = Instant.parse("2026-10-13T01:00:00Z");

        service.mergeGarminSnapshot(176, 290, "2026-10-06T00:50:55.648", fetched);

        AthleteIntensityProfileHistory row = ledger().get(0);
        assertThat(row.getSource()).isEqualTo(AthleteIntensityProfileHistory.Source.GARMIN);
        assertThat(row.isApplied()).isTrue();
        // verbatim: no zone guess, no reformatting, millisecond suffix intact
        assertThat(row.getGarminCalendarDateRaw()).isEqualTo("2026-10-06T00:50:55.648");
        assertThat(row.getGarminFetchedAt()).isEqualTo(fetched);
        assertThat(row.getPreviousHeartRateBpm()).isEqualTo(180);
        assertThat(row.getPreviousPaceSecondsPerKm()).isEqualTo(300);

        LactateThresholdProvenance p = service.provenance();
        assertThat(p.sourceTracked()).isTrue();
        assertThat(p.source()).isEqualTo("GARMIN");
        assertThat(p.lastGarminObservationCalendarDateRaw()).isEqualTo("2026-10-06T00:50:55.648");
    }

    @Test
    void aSameValueGarminReObservationIsRecordedButNeverCertifiesALegacyOrigin() {
        // legacy value: written before the ledger existed (direct table write)
        profiles.save(new AthleteIntensityProfile(athleteId(), 176, 290));
        assertThat(service.provenance().sourceTracked()).isFalse();

        // Garmin re-reports the same values with a NEWER measurement date
        var result = service.mergeGarminSnapshot(176, 290, "2026-10-12T08:00:00.0", Instant.parse("2026-10-13T02:00:00Z"));

        assertThat(result.updated()).isFalse();
        AthleteIntensityProfileHistory row = ledger().get(0);
        assertThat(row.isApplied()).isFalse(); // observation, not a change
        assertThat(row.getGarminCalendarDateRaw()).isEqualTo("2026-10-12T08:00:00.0");

        LactateThresholdProvenance p = service.provenance();
        assertThat(p.sourceTracked()).as("an observation must not retro-certify a legacy value").isFalse();
        assertThat(p.source()).isNull();
        assertThat(p.lastAppliedChangeAt()).isNull();
        // but the freshness evidence IS visible
        assertThat(p.lastGarminObservationCalendarDateRaw()).isEqualTo("2026-10-12T08:00:00.0");
        assertThat(p.lastGarminObservationFetchedAt()).isEqualTo(Instant.parse("2026-10-13T02:00:00Z"));
    }

    @Test
    void anIdenticalManualPutIsAnObservationToo() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(176, 290));
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(176, 290));

        List<AthleteIntensityProfileHistory> rows = ledger();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).isApplied()).isFalse();
        assertThat(rows.get(1).isApplied()).isTrue();
        assertThat(service.provenance().sourceTracked()).isTrue(); // the applied row still matches
    }

    @Test
    void provenanceGoesUntrackedWhenTheProfileDivergesFromTheLedger() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(176, 290));
        // simulate an out-of-band change the ledger never saw
        jdbc.update("update athlete_intensity_profile set lactate_threshold_heart_rate_bpm = 170");

        LactateThresholdProvenance p = service.provenance();
        assertThat(p.sourceTracked()).isFalse();
        assertThat(p.source()).isNull();
        assertThat(p.lastAppliedChangeAt()).as("the last audited change stays reported").isNotNull();
    }

    @Test
    void noProfileAndNoHistoryMeansNothingIsClaimed() {
        LactateThresholdProvenance p = service.provenance();

        assertThat(p.lactateThresholdHeartRateBpm()).isNull();
        assertThat(p.sourceTracked()).isFalse();
        assertThat(p.source()).isNull();
        assertThat(p.lastAppliedChangeAt()).isNull();
        assertThat(p.lastGarminObservationCalendarDateRaw()).isNull();
    }

    // ---- atomicity and concurrency --------------------------------------------------------------

    @Test
    void aFailedLedgerInsertRollsTheProfileChangeBack() {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(176, 290));
        doThrow(new RuntimeException("ledger unavailable")).when(history).save(any());

        assertThatThrownBy(() -> service.replaceDefaultProfile(new AthleteIntensityProfileRequest(160, 320)))
                .hasMessageContaining("ledger unavailable");

        reset(history);
        assertThat(service.getDefaultProfile().lactateThresholdHeartRateBpm())
                .as("the profile change must roll back with the failed ledger insert").isEqualTo(176);
        assertThat(ledger()).hasSize(1);
    }

    @Test
    void concurrentWritesSerializeOnTheRowLockAndKeepTheChainConsistent() throws Exception {
        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(170, 300));
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (n % 2 == 0) {
                        service.replaceDefaultProfile(new AthleteIntensityProfileRequest(171 + n, 300 + n));
                    } else {
                        service.mergeGarminSnapshot(171 + n, 300 + n, "2026-10-13T0" + n + ":00:00.0",
                                Instant.parse("2026-10-13T03:00:00Z"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Every row's previous-values snapshot must equal the values of the row it replaced:
        // with the pessimistic row lock the applied chain is strictly consistent, whatever the
        // interleaving was.
        List<AthleteIntensityProfileHistory> oldestFirst = new java.util.ArrayList<>(ledger());
        java.util.Collections.reverse(oldestFirst);
        Integer expectedPrevBpm = null;
        Integer expectedPrevPace = null;
        for (AthleteIntensityProfileHistory row : oldestFirst) {
            assertThat(row.getPreviousHeartRateBpm()).isEqualTo(expectedPrevBpm);
            assertThat(row.getPreviousPaceSecondsPerKm()).isEqualTo(expectedPrevPace);
            if (row.isApplied()) {
                expectedPrevBpm = row.getLactateThresholdHeartRateBpm();
                expectedPrevPace = row.getLactateThresholdPaceSecondsPerKm();
            }
        }
        // and the current profile equals the last applied row (provenance agrees)
        var current = service.getDefaultProfile();
        assertThat(current.lactateThresholdHeartRateBpm()).isEqualTo(expectedPrevBpm);
        assertThat(current.lactateThresholdPaceSecondsPerKm()).isEqualTo(expectedPrevPace);
        assertThat(service.provenance().sourceTracked()).isTrue();
    }
}
