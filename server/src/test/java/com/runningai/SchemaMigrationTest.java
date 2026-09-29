package com.runningai;

import com.runningai.activity.Activity;
import com.runningai.activity.ActivityRepository;
import com.runningai.activity.ActivityType;
import com.runningai.activity.ExternalSource;
import com.runningai.athlete.AthleteService;
import com.runningai.integration.garmin.GarminSyncState;
import com.runningai.integration.garmin.GarminSyncStateRepository;
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
    private EntityManager entityManager;

    @Test
    void allMigrationsAppliedSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).extracting(MigrationInfo::getVersion)
                .extracting(Object::toString)
                .containsExactly("1", "2", "3", "4");
        assertThat(applied).extracting(MigrationInfo::getState)
                .containsOnly(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();

        Integer historyRows = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version in ('1', '2', '3', '4')",
                Integer.class);
        assertThat(historyRows).isEqualTo(4);
    }

    @Test
    void expectedTablesExist() {
        List<String> tables = jdbcTemplate.queryForList(
                "select lower(table_name) from information_schema.tables "
                        + "where lower(table_name) in "
                        + "('athlete', 'activity', 'activity_raw', 'garmin_sync_state', 'flyway_schema_history')",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "athlete", "activity", "activity_raw", "garmin_sync_state", "flyway_schema_history");
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
}
