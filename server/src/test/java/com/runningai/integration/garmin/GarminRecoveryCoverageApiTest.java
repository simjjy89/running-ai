package com.runningai.integration.garmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runningai.athlete.AthleteService;
import com.runningai.coach.SessionConstraints;
import com.runningai.coach.TrainingContextV2Builder;
import com.runningai.integration.garmin.GarminConnectorException.Reason;
import com.runningai.recovery.RecoveryRepository;
import com.runningai.recovery.RecoverySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8A: a missing recovery day stops being ambiguous.
 * <p>
 * Before this phase, "no row in {@code garmin_recovery_daily}" could equally mean nobody had asked
 * Garmin, Garmin had genuinely recorded nothing that night, or the call had failed - three cases
 * needing three different responses from an operator planning a backfill. These tests pin down that
 * the three are now distinguishable, and that the distinction is actually used: a backfill asked for
 * only the missing days must retry a failure and must NOT re-ask for a night Garmin has already said
 * it has nothing for.
 * <p>
 * Real mapper, storage, schema and REST stack; only the connector client is mocked. Fixed clock:
 * 2026-10-02 08:00 in Asia/Seoul.
 */
@SpringBootTest(properties = "running-ai.garmin.recovery-sync.backfill-delay=0s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GarminRecoveryCoverageApiTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-01T23:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecoveryRepository recoveryRepository;

    @Autowired
    private GarminRecoveryCollectionRepository collectionRepository;

    @Autowired
    private GarminRecoverySyncService service;

    @Autowired
    private TrainingContextV2Builder contextV2Builder;

    @Autowired
    private AthleteService athleteService;

    @MockitoBean
    private GarminRecoveryClient client;

    @AfterEach
    void cleanUp() {
        recoveryRepository.deleteAll();
        collectionRepository.deleteAll();
    }

    private JsonNode full(LocalDate date) throws Exception {
        return objectMapper.readTree(GarminRecoveryMapperTest.FULL.replace("2026-10-02", date.toString()));
    }

    /** Only resting HR arrives; HRV and sleep are absent, Body Battery failed on Garmin's side. */
    private JsonNode onlyRestingHr(LocalDate date, int bpm) throws Exception {
        return objectMapper.readTree("""
                {"date": "%s", "metrics": {
                  "hrv": {"status": "NO_DATA", "data": null},
                  "sleep": {"status": "NO_DATA", "data": null},
                  "restingHeartRate": {"status": "OK", "data": {"value": %d}},
                  "bodyBattery": {"status": "ERROR", "data": null, "error": "GARMIN_UPSTREAM_ERROR"},
                  "stress": {"status": "NO_DATA", "data": null}}}
                """.formatted(date, bpm));
    }

    /** A night the watch was not worn: the call succeeds, and Garmin has nothing to give. */
    private JsonNode nothingAtAll(LocalDate date) throws Exception {
        return objectMapper.readTree("""
                {"date": "%s", "metrics": {
                  "hrv": {"status": "NO_DATA", "data": null},
                  "sleep": {"status": "NO_DATA", "data": null},
                  "restingHeartRate": {"status": "NO_DATA", "data": null},
                  "bodyBattery": {"status": "NO_DATA", "data": null},
                  "stress": {"status": "NO_DATA", "data": null}}}
                """.formatted(date));
    }

    private GarminRecoveryCoverageStatus statusOf(LocalDate date) {
        return service.coverage(TODAY, 7).days().stream()
                .filter(day -> day.date().equals(date))
                .findFirst().orElseThrow()
                .status();
    }

    /** Scenario 7: some metrics are missing. The day is stored, and exactly what is absent is reported. */
    @Test
    void aPartiallyDeliveredDayIsStoredAndItsMissingMetricsAreNamed() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(onlyRestingHr(TODAY, 52));

        service.syncDay(TODAY);

        assertThat(statusOf(TODAY)).isEqualTo(GarminRecoveryCoverageStatus.PARTIAL);
        GarminRecoveryCoverageResponse.DayCoverage day = service.coverage(TODAY, 7).days().stream()
                .filter(d -> d.date().equals(TODAY)).findFirst().orElseThrow();
        assertThat(day.metrics()).containsEntry("restingHeartRate", true);
        assertThat(day.missingMetrics()).containsExactly("hrv", "sleep", "bodyBattery", "stress");
        assertThat(day.lastCollectionOutcome()).isEqualTo(GarminRecoveryCollectionOutcome.STORED);
        assertThat(day.lastFailureCode()).isNull();

        // Garmin's own reason per metric is kept, so "no reading that night" stays distinct from
        // "Garmin's call for that metric broke".
        GarminRecoveryCollection record = collectionRepository
                .findByAthleteIdAndRecoveryDate(athleteId(), TODAY).orElseThrow();
        assertThat(record.getRestingHeartRateOutcome()).isEqualTo(GarminRecoveryMetricOutcome.AVAILABLE);
        assertThat(record.getHrvOutcome()).isEqualTo(GarminRecoveryMetricOutcome.NO_DATA);
        assertThat(record.getBodyBatteryOutcome()).isEqualTo(GarminRecoveryMetricOutcome.ERROR);
    }

    /**
     * Scenario 8: Garmin has no data for the date at all. No recovery row is created - an empty row
     * would read as "Garmin had data" - but the attempt is recorded, which is what turns the missing
     * row from a question into an answer.
     */
    @Test
    void aDayGarminHasNothingForIsRecordedAsConfirmedNoDataWithoutStoringAnEmptyRow() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(nothingAtAll(TODAY));

        service.syncDay(TODAY);

        assertThat(recoveryRepository.findAll()).isEmpty();
        assertThat(statusOf(TODAY)).isEqualTo(GarminRecoveryCoverageStatus.CONFIRMED_NO_DATA);
        assertThat(collectionRepository.findByAthleteIdAndRecoveryDate(athleteId(), TODAY).orElseThrow().getOutcome())
                .isEqualTo(GarminRecoveryCollectionOutcome.NO_DATA);
    }

    /** A failed call is recorded as a failure for that day, and stores nothing. */
    @Test
    void aFailedCallIsRecordedAsCollectionFailedWithItsCode() throws Exception {
        when(client.fetchDay(TODAY)).thenThrow(new GarminConnectorException(Reason.RATE_LIMITED, 429, "rate limited"));

        try {
            service.syncDay(TODAY);
        } catch (GarminConnectorException expected) {
            // the failure is the subject of the test; what matters is what it left behind
        }

        assertThat(recoveryRepository.findAll()).isEmpty();
        assertThat(statusOf(TODAY)).isEqualTo(GarminRecoveryCoverageStatus.COLLECTION_FAILED);
        GarminRecoveryCollection record = collectionRepository
                .findByAthleteIdAndRecoveryDate(athleteId(), TODAY).orElseThrow();
        assertThat(record.getFailureCode()).isEqualTo("GARMIN_RATE_LIMITED");
        // Nothing came back, so no per-metric verdict may be invented.
        assertThat(record.getHrvOutcome()).isNull();
        assertThat(record.getStressOutcome()).isNull();
    }

    /** A day nobody has asked about is not the same as a day Garmin has nothing for. */
    @Test
    void aDayNobodyHasAskedAboutIsNotCollected() {
        assertThat(statusOf(TODAY.minusDays(3))).isEqualTo(GarminRecoveryCoverageStatus.NOT_COLLECTED);
    }

    /**
     * The whole point of recording the outcomes: a repeat backfill asks only for the days that could
     * still gain something. A complete day and a day Garmin has already said it has nothing for are
     * both skipped; a failed day and an untouched day are retried.
     */
    @Test
    void onlyMissingSkipsCompleteAndConfirmedEmptyDaysAndRetriesTheRest() throws Exception {
        LocalDate complete = TODAY;
        LocalDate empty = TODAY.minusDays(1);
        LocalDate failed = TODAY.minusDays(2);
        LocalDate untouched = TODAY.minusDays(3);

        when(client.fetchDay(complete)).thenReturn(full(complete));
        when(client.fetchDay(empty)).thenReturn(nothingAtAll(empty));
        when(client.fetchDay(failed)).thenThrow(new GarminConnectorException(Reason.UPSTREAM_ERROR, 502, "upstream"));
        when(client.fetchDay(untouched)).thenReturn(full(untouched));

        // A plain backfill asks for every day, and stops at the first failure (existing behaviour).
        GarminRecoveryBackfillResponse first = service.backfill(TODAY, 4, false);
        assertThat(first.completed()).isFalse();
        assertThat(first.stoppedAt()).isEqualTo(failed);
        assertThat(first.skippedDays()).isZero();

        assertThat(statusOf(complete)).isEqualTo(GarminRecoveryCoverageStatus.COMPLETE);
        assertThat(statusOf(empty)).isEqualTo(GarminRecoveryCoverageStatus.CONFIRMED_NO_DATA);
        assertThat(statusOf(failed)).isEqualTo(GarminRecoveryCoverageStatus.COLLECTION_FAILED);
        assertThat(statusOf(untouched)).isEqualTo(GarminRecoveryCoverageStatus.NOT_COLLECTED);

        // The second run is told to fetch only what is still missing. doReturn, not when(...): the
        // day is stubbed to throw, and when(mock.call()) would run that stub while re-stubbing it.
        doReturn(full(failed)).when(client).fetchDay(failed);
        GarminRecoveryBackfillResponse second = service.backfill(TODAY, 4, true);

        assertThat(second.completed()).isTrue();
        assertThat(second.onlyMissing()).isTrue();
        assertThat(second.fetchedDays()).isEqualTo(2);
        assertThat(second.skippedDays()).isEqualTo(2);

        // Exactly one call per day was ever made for the two skipped days - the second run did not
        // ask Garmin again for the night it has already answered "nothing" for.
        verify(client, times(1)).fetchDay(complete);
        verify(client, times(1)).fetchDay(empty);
        verify(client, times(2)).fetchDay(failed);
        verify(client, times(1)).fetchDay(untouched);
        verifyNoMoreInteractions(client);

        assertThat(statusOf(failed)).isEqualTo(GarminRecoveryCoverageStatus.COMPLETE);
        assertThat(statusOf(untouched)).isEqualTo(GarminRecoveryCoverageStatus.COMPLETE);
    }

    /**
     * A partially stored day is still worth re-asking: Garmin fills a night in late. Re-fetching must
     * add the missing metrics without overwriting what is already stored - the merge rule the
     * recovery store has always had, which {@code onlyMissing} must not quietly change.
     */
    @Test
    void onlyMissingRefetchesAPartialDayAndTheRefetchNeverOverwritesAStoredValue() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(onlyRestingHr(TODAY, 52));
        service.backfill(TODAY, 1, true);
        assertThat(statusOf(TODAY)).isEqualTo(GarminRecoveryCoverageStatus.PARTIAL);

        // Garmin now has the rest of the night, but no longer reports resting HR.
        when(client.fetchDay(TODAY)).thenReturn(objectMapper.readTree("""
                {"date": "%s", "metrics": {
                  "hrv": {"status": "OK", "data": {"lastNightAvg": 52.0, "weeklyAvg": 48.5, "status": "BALANCED"}},
                  "sleep": {"status": "OK", "data": {"sleepTimeSeconds": 25200, "sleepScore": 84}},
                  "restingHeartRate": {"status": "NO_DATA", "data": null},
                  "bodyBattery": {"status": "OK", "data": {"highest": 81, "lowest": 40, "charged": 58, "drained": 32}},
                  "stress": {"status": "OK", "data": {"avgStressLevel": 29, "maxStressLevel": 88}}}}
                """.formatted(TODAY)));
        GarminRecoveryBackfillResponse second = service.backfill(TODAY, 1, true);

        assertThat(second.fetchedDays()).isEqualTo(1);
        assertThat(second.skippedDays()).isZero();
        RecoverySnapshot stored = recoveryRepository
                .findByAthleteIdAndRecoveryDate(athleteId(), TODAY).orElseThrow();
        assertThat(stored.values().sleepScore()).isEqualTo(84);
        // The metric that stopped being reported keeps the value collected earlier.
        assertThat(stored.values().restingHeartRateBpm()).isEqualTo(52);
        assertThat(statusOf(TODAY)).isEqualTo(GarminRecoveryCoverageStatus.COMPLETE);
    }

    /** Scenario 9: a backfill is immediately visible to the coach context, with no extra step. */
    @Test
    void backfilledRecoveryDaysReachTrainingContextV2() throws Exception {
        assertThat(contextV2Builder.build(TODAY, new SessionConstraints(null, null, null, null, null)).getDataCoverage().getRecoveryDaysAvailable()).isZero();

        for (int i = 0; i < 5; i++) {
            LocalDate date = TODAY.minusDays(i);
            when(client.fetchDay(date)).thenReturn(full(date));
        }
        GarminRecoveryBackfillResponse result = service.backfill(TODAY, 5, false);
        assertThat(result.completed()).isTrue();
        assertThat(result.fetchedDays()).isEqualTo(5);

        assertThat(contextV2Builder.build(TODAY, new SessionConstraints(null, null, null, null, null)).getDataCoverage().getRecoveryDaysAvailable()).isEqualTo(5);
    }

    /** The coverage report is read-only: it answers from the database and calls nothing. */
    @Test
    void theCoverageEndpointAnswersFromTheDatabaseAndNeverCallsGarmin() throws Exception {
        when(client.fetchDay(TODAY)).thenReturn(onlyRestingHr(TODAY, 52));
        service.syncDay(TODAY);

        mockMvc.perform(get("/api/v1/garmin/recovery-sync/coverage")
                        .param("endDate", TODAY.toString())
                        .param("days", "3")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDays").value(3))
                .andExpect(jsonPath("$.statusCounts.PARTIAL").value(1))
                .andExpect(jsonPath("$.statusCounts.NOT_COLLECTED").value(2))
                .andExpect(jsonPath("$.datesWorthFetching.length()").value(3))
                .andExpect(jsonPath("$.days[2].missingMetrics[0]").value("hrv"));

        verify(client, times(1)).fetchDay(any());
    }

    /** A window larger than the configured coverage maximum is refused rather than silently clamped. */
    @Test
    void anOutOfRangeCoverageWindowIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/garmin/recovery-sync/coverage").param("days", "0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COVERAGE_DAYS"));
        mockMvc.perform(get("/api/v1/garmin/recovery-sync/coverage").param("days", "400"))
                .andExpect(status().isUnprocessableEntity());
        verify(client, never()).fetchDay(any());
    }

    /** The backfill request body keeps working without the new flag. */
    @Test
    void aBackfillRequestWithoutTheFlagBehavesExactlyAsBefore() throws Exception {
        when(client.fetchDay(any())).thenReturn(full(TODAY));

        mockMvc.perform(post("/api/v1/garmin/recovery-sync/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onlyMissing").value(false))
                .andExpect(jsonPath("$.fetchedDays").value(1))
                .andExpect(jsonPath("$.skippedDays").value(0));
    }

    private Long athleteId() {
        return athleteService.getDefaultAthlete().getId();
    }
}
