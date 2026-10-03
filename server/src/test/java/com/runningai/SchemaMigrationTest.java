package com.runningai;

import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ActivityType;
import com.runningai.activity.ExternalSource;
import com.runningai.athlete.AthleteIntensityProfile;
import com.runningai.athlete.AthleteIntensityProfileRepository;
import com.runningai.athlete.AthleteService;
import com.runningai.integration.garmin.GarminSyncState;
import com.runningai.integration.garmin.GarminSyncStateRepository;
import com.runningai.enrichment.IntervalsPayloadType;
import com.runningai.enrichment.IntervalsRawPayloadEntity;
import com.runningai.enrichment.IntervalsRawPayloadRepository;
import com.runningai.recovery.RecoveryRepository;
import com.runningai.recovery.RecoverySnapshot;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the schema comes from Flyway (not Hibernate) and that the
 * constraints the application relies on really exist in the database.
 * The application context only starts if Hibernate's schema validation passes,
 * so a mapping/migration mismatch fails every test in this class.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private AthleteService athleteService;

    @Autowired
    private GarminSyncStateRepository garminSyncStateRepository;

    @Autowired
    private AthleteIntensityProfileRepository athleteIntensityProfileRepository;

    @Autowired
    private RecoveryRepository recoveryRepository;

    @Autowired
    private IntervalsRawPayloadRepository intervalsRawPayloadRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void allMigrationsAppliedSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).extracting(MigrationInfo::getVersion)
                .extracting(Object::toString)
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                        "17", "18");
        assertThat(applied).extracting(MigrationInfo::getState)
                .containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();

        Integer historyRows = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version in ('1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12', '13', '14', '15', '16', '17', '18')",
                Integer.class);
        assertThat(historyRows).isEqualTo(18);
    }

    @Test
    void expectedTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "select lower(table_name) from information_schema.tables "
                        + "where lower(table_name) in "
                        + "('athlete', 'activity', 'activity_raw', 'garmin_sync_state', 'athlete_intensity_profile', "
                        + "'workout_draft', 'garmin_recovery_daily', 'workout_draft_approval', 'workout_draft_publication', "
                        + "'activity_raw_payload', 'activity_detail', 'activity_lap', 'activity_zone', 'activity_sample', "
                        + "'activity_detail_collection', 'activity_analysis', 'activity_analysis_interval', "
                        + "'activity_analysis_interval_group', "
                        + "'activity_source_link', 'intervals_raw_payload', 'activity_intervals_metrics', "
                        + "'intervals_fitness_daily', "
                        + "'flyway_schema_history')",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "athlete", "activity", "activity_raw", "garmin_sync_state", "athlete_intensity_profile",
                "workout_draft", "garmin_recovery_daily", "workout_draft_approval", "workout_draft_publication",
                "activity_raw_payload", "activity_detail", "activity_lap", "activity_zone", "activity_sample",
                "activity_detail_collection", "activity_analysis", "activity_analysis_interval",
                "activity_analysis_interval_group",
                "activity_source_link", "intervals_raw_payload", "activity_intervals_metrics",
                "intervals_fitness_daily",
                "flyway_schema_history");
    }

    /**
     * V14: sample fidelity is recorded, not inferred. The columns only carry meaning for the sample
     * stream, so every one of them has to be nullable — a part that has no stream records nothing.
     */
    @Test
    void sampleFidelityColumnsExistAndAreNullable() {
        List<String> nullableColumns = jdbcTemplate.queryForList(
                "select lower(column_name) from information_schema.columns "
                        + "where lower(table_name) = 'activity_detail_collection' and is_nullable = 'YES' "
                        + "and lower(column_name) in ('requested_max_chart_size', 'source_metrics_count', "
                        + "'source_total_metrics_count', 'sample_completeness')",
                String.class);

        assertThat(nullableColumns).containsExactlyInAnyOrder(
                "requested_max_chart_size", "source_metrics_count", "source_total_metrics_count",
                "sample_completeness");
    }

    @Test
    @Transactional
    void sampleCompletenessIsConstrainedToTheKnownValuesByTheDatabase() {
        Long activityId = activityRepository.save(new Activity(
                athleteService.getDefaultAthlete().getId(), ExternalSource.GARMIN, "9900000001",
                ActivityType.RUN, Instant.parse("2026-09-30T00:00:00Z"), 1800, null, null, null)).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into activity_detail_collection "
                        + "(activity_id, payload_type, status, attempted_at, sample_completeness) "
                        + "values (?, 'ACTIVITY_DETAILS_STREAM', 'NORMALIZED', ?, 'PROBABLY_FULL')",
                activityId, java.sql.Timestamp.from(Instant.parse("2026-09-30T00:00:00Z"))))
                .isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    @Test
    void rawPayloadColumnIsAJsonType() {
        String dataType = jdbcTemplate.queryForObject(
                "select lower(data_type) from information_schema.columns "
                        + "where lower(table_name) = 'activity_raw' and lower(column_name) = 'payload'",
                String.class);

        // JSON on H2 (tests), JSONB on PostgreSQL (local/runtime).
        assertThat(dataType).isIn("json", "jsonb");
    }

    @Test
    @Transactional
    void uniqueConstraintOnExternalIdentityIsEnforcedByTheDatabase() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Instant startedAt = Instant.parse("2026-09-28T21:30:00Z");

        activityRepository.save(new Activity(athleteId, ExternalSource.GARMIN, "db-unique",
                ActivityType.RUN, startedAt, 3600, 10000.0, 155, 172));
        entityManager.flush();

        // IDENTITY ids make the insert run on save(), so the violation surfaces there or on flush.
        assertThatThrownBy(() -> {
            activityRepository.save(new Activity(athleteId, ExternalSource.GARMIN, "db-unique",
                    ActivityType.TREADMILL_RUN, startedAt, 1800, null, null, null));
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    @Test
    @Transactional
    void uniqueConstraintOnGarminSyncStateAthleteIsEnforcedByTheDatabase() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Instant now = Instant.parse("2026-09-30T00:00:00Z");

        garminSyncStateRepository.save(new GarminSyncState(athleteId, now, now));
        entityManager.flush();

        assertThatThrownBy(() -> {
            garminSyncStateRepository.save(new GarminSyncState(athleteId, now, now));
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    @Test
    @Transactional
    void uniqueConstraintOnAthleteIntensityProfileAthleteIsEnforcedByTheDatabase() {
        Long athleteId = athleteService.getDefaultAthlete().getId();

        athleteIntensityProfileRepository.save(new AthleteIntensityProfile(athleteId, 170, 300));
        entityManager.flush();

        assertThatThrownBy(() -> {
            athleteIntensityProfileRepository.save(new AthleteIntensityProfile(athleteId, 172, 305));
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    @Test
    @Transactional
    void uniqueConstraintOnRecoveryAthleteAndDateIsEnforcedByTheDatabase() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        LocalDate day = LocalDate.of(2026, 10, 2);

        recoveryRepository.save(new RecoverySnapshot(athleteId, day));
        entityManager.flush();

        assertThatThrownBy(() -> {
            recoveryRepository.save(new RecoverySnapshot(athleteId, day));
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    // Each of these ends its transaction on the first failing statement: on PostgreSQL a failed insert
    // aborts the transaction, so one violation per @Transactional test (same reason as the recovery test).

    private Long saveActivity(String externalId) {
        return activityRepository.save(new Activity(
                athleteService.getDefaultAthlete().getId(), ExternalSource.GARMIN, externalId,
                ActivityType.RUN, Instant.parse("2026-10-01T00:00:00Z"), 1800, null, null, null)).getId();
    }

    private void insertSourceLink(Long activityId, String externalActivityId, String method) {
        jdbcTemplate.update("insert into activity_source_link "
                        + "(activity_id, external_source, external_activity_id, match_method, matched_at, created_at, updated_at) "
                        + "values (?, 'INTERVALS_ICU', ?, ?, current_timestamp, current_timestamp, current_timestamp)",
                activityId, externalActivityId, method);
    }

    @Test
    @Transactional
    void sourceLinkRejectsASecondClaimOnTheSameExternalActivity() {
        insertSourceLink(saveActivity("9900000017"), "i-unique", "SOURCE_ID");
        Long other = saveActivity("9900000018");

        assertThatThrownBy(() -> insertSourceLink(other, "i-unique", "SOURCE_ID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void sourceLinkRejectsASecondLinkOfTheSameSourceOnOneActivity() {
        Long activityId = saveActivity("9900000019");
        insertSourceLink(activityId, "i-first", "SOURCE_ID");

        assertThatThrownBy(() -> insertSourceLink(activityId, "i-other", "COMPOSITE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void sourceLinkRejectsAnUnknownMatchMethod() {
        Long activityId = saveActivity("9900000020");

        assertThatThrownBy(() -> insertSourceLink(activityId, "i-method", "GUESSED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void uniqueConstraintOnIntervalsRawPayloadIsEnforcedByTheDatabase() {
        Long athleteId = athleteService.getDefaultAthlete().getId();
        Instant fetchedAt = Instant.parse("2026-10-01T00:00:00Z");

        intervalsRawPayloadRepository.save(new IntervalsRawPayloadEntity(athleteId, IntervalsPayloadType.WELLNESS_DAY,
                "2026-10-01", null, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), fetchedAt));
        entityManager.flush();

        assertThatThrownBy(() -> {
            intervalsRawPayloadRepository.save(new IntervalsRawPayloadEntity(athleteId, IntervalsPayloadType.WELLNESS_DAY,
                    "2026-10-01", null, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), fetchedAt));
            entityManager.flush();
        }).isInstanceOfAny(DataIntegrityViolationException.class, jakarta.persistence.PersistenceException.class);
    }

    @Test
    @Transactional
    void uniqueConstraintOnIntervalsFitnessDailyIsEnforcedByTheDatabase() {
        Long athleteId = athleteService.getDefaultAthlete().getId();

        jdbcTemplate.update("insert into intervals_fitness_daily "
                        + "(athlete_id, fitness_date, created_at, updated_at, fetched_at) "
                        + "values (?, '2026-10-01', current_timestamp, current_timestamp, current_timestamp)",
                athleteId);

        assertThatThrownBy(() -> jdbcTemplate.update("insert into intervals_fitness_daily "
                        + "(athlete_id, fitness_date, created_at, updated_at, fetched_at) "
                        + "values (?, '2026-10-01', current_timestamp, current_timestamp, current_timestamp)",
                athleteId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void recoveryCheckConstraintsRejectNegativeValues() {
        // no test transaction: on PostgreSQL the first failed insert would abort it for the second
        Long athleteId = athleteService.getDefaultAthlete().getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into garmin_recovery_daily (athlete_id, recovery_date, resting_heart_rate_bpm, created_at, updated_at) "
                        + "values (?, ?, ?, current_timestamp, current_timestamp)",
                athleteId, LocalDate.of(2026, 10, 1), 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into garmin_recovery_daily (athlete_id, recovery_date, stress_average, created_at, updated_at) "
                        + "values (?, ?, ?, current_timestamp, current_timestamp)",
                athleteId, LocalDate.of(2026, 10, 1), -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
