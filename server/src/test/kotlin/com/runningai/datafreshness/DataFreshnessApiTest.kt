package com.runningai.datafreshness

import com.runningai.activity.Activity
import com.runningai.activity.ActivityRawRepository
import com.runningai.activity.ActivityRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.athlete.AthleteService
import com.runningai.integration.garmin.GarminActivitySource
import com.runningai.integration.garmin.GarminConnectorException
import com.runningai.integration.garmin.GarminRecoveryClient
import com.runningai.integration.garmin.GarminRecoveryCollectionRepository
import com.runningai.integration.garmin.GarminSyncAttemptRepository
import com.runningai.integration.garmin.GarminSyncOperationService
import com.runningai.integration.garmin.GarminSyncStateRepository
import com.runningai.integration.intervals.IntervalsReadClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate

/**
 * Phase 8A: one read-only place to ask how current every source is, and whether that answer can be
 * trusted.
 *
 * The question this endpoint exists to answer is not "is there recent data?" - `coach/data-refresh`
 * already answers that, by fetching. It is "can an absence of recent data be attributed?", which only
 * the recorded collection outcomes can settle. These tests pin down that the report says so honestly:
 * a tracked source carries its last attempt and failure, an untracked one admits it is untracked
 * rather than implying nothing new exists, and nothing here ever calls an external system.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataFreshnessApiTest {

    private val date: LocalDate = LocalDate.of(2026, 10, 15)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var service: DataFreshnessService

    @Autowired
    private lateinit var activities: ActivityRepository

    @Autowired
    private lateinit var activityRaws: ActivityRawRepository

    @Autowired
    private lateinit var syncStateRepository: GarminSyncStateRepository

    @Autowired
    private lateinit var syncAttemptRepository: GarminSyncAttemptRepository

    @Autowired
    private lateinit var recoveryCollectionRepository: GarminRecoveryCollectionRepository

    @Autowired
    private lateinit var syncOperationService: GarminSyncOperationService

    @Autowired
    private lateinit var athleteService: AthleteService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var activitySource: GarminActivitySource

    @MockitoBean
    private lateinit var recoveryClient: GarminRecoveryClient

    @MockitoBean
    private lateinit var intervalsReadClient: IntervalsReadClient

    private var athleteId: Long = 0

    @BeforeEach
    fun setUp() {
        athleteId = athleteService.defaultAthlete.id
    }

    @AfterEach
    fun cleanUp() {
        // activity_raw references activity, so the raw rows a real sync wrote go first.
        activityRaws.deleteAll()
        activities.deleteAll()
        syncStateRepository.deleteAll()
        syncAttemptRepository.deleteAll()
        recoveryCollectionRepository.deleteAll()
    }

    private fun storeActivity(source: ExternalSource, externalId: String, day: LocalDate) {
        activities.save(
            Activity(
                athleteId, source, externalId, ActivityType.RUN,
                day.atTime(6, 0).toInstant(java.time.ZoneOffset.UTC), 1800, 5000.0, 140, 160,
            ),
        )
    }

    /** One Garmin activity-list item, enough for a sync to have something real to ingest. */
    private fun garminListItem(): JsonNode = objectMapper.readTree(
        """
        {"activityId": 9001, "activityType": {"typeKey": "running"}, "startTimeGMT": "2026-10-13 06:00:00",
         "duration": 1800.0, "distance": 5000.0, "averageHR": 140.0, "maxHR": 160.0}
        """,
    )

    private fun sourceOf(response: DataFreshnessResponse, source: DataSource): DataSourceFreshness =
        response.sources.first { it.source == source }

    @Test
    fun `the report is answered from the database and calls no external system`() {
        storeActivity(ExternalSource.GARMIN, "g-1", date.minusDays(2))

        mockMvc.perform(get("/api/v1/data-freshness").param("date", date.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value(date.toString()))
            .andExpect(jsonPath("$.windowDays").value(28))
            .andExpect(jsonPath("$.sources.length()").value(5))

        verifyNoInteractions(activitySource, recoveryClient, intervalsReadClient)
    }

    /**
     * An Intervals or Strava import must never make the Garmin sync look current: each source reports
     * its own newest data, because they go stale for entirely different reasons.
     */
    @Test
    fun `each source reports its own newest data, never another source's`() {
        storeActivity(ExternalSource.GARMIN, "g-1", date.minusDays(9))
        storeActivity(ExternalSource.INTERVALS_ICU, "i-1", date.minusDays(1))
        storeActivity(ExternalSource.STRAVA, "s-1", date.minusDays(40))

        val response = service.freshness(date, 28)

        assertThat(sourceOf(response, DataSource.GARMIN_ACTIVITY).latestDataDate).isEqualTo(date.minusDays(9))
        assertThat(sourceOf(response, DataSource.GARMIN_ACTIVITY).dataAgeDays).isEqualTo(9)
        assertThat(sourceOf(response, DataSource.INTERVALS_ACTIVITY).latestDataDate).isEqualTo(date.minusDays(1))
        assertThat(sourceOf(response, DataSource.STRAVA_ACTIVITY).latestDataDate).isEqualTo(date.minusDays(40))
    }

    /**
     * The window bounds the COUNTS, never the search for the newest stored date: a source last
     * updated outside the window must report its real age, not a null that reads as "no data at all".
     */
    @Test
    fun `a source older than the window reports its real age rather than looking empty`() {
        storeActivity(ExternalSource.GARMIN, "g-old", date.minusDays(60))

        val response = service.freshness(date, 28)
        val garmin = sourceOf(response, DataSource.GARMIN_ACTIVITY)

        assertThat(garmin.latestDataDate).isEqualTo(date.minusDays(60))
        assertThat(garmin.dataAgeDays).isEqualTo(60)
        assertThat(garmin.storedRecordCount).isZero()
    }

    /**
     * The distinction the whole phase is about: after a failure, the report separates "when it last
     * worked" from "what happened last time", so a stale source cannot be mistaken for a quiet one.
     */
    @Test
    fun `a failed sync is reported as a failure without disturbing the last successful collection`() {
        `when`(activitySource.fetchActivities(anyInt(), anyInt()))
            .thenReturn(listOf(garminListItem()))
        syncOperationService.runSync()
        val successAt = syncStateRepository.findByAthleteId(athleteId).orElseThrow().lastSuccessfulSyncAt

        `when`(activitySource.fetchActivities(anyInt(), anyInt()))
            .thenThrow(GarminConnectorException(GarminConnectorException.Reason.RATE_LIMITED, 429, "rate limited"))
        assertThatThrownBy { syncOperationService.runSync() }.isInstanceOf(GarminConnectorException::class.java)

        val garmin = sourceOf(service.freshness(date, 28), DataSource.GARMIN_ACTIVITY)
        assertThat(garmin.collectionTracked).isTrue()
        assertThat(garmin.lastAttemptOutcome).isEqualTo("FAILED")
        assertThat(garmin.lastFailureCode).isEqualTo("GARMIN_RATE_LIMITED")
        assertThat(garmin.consecutiveFailureCount).isEqualTo(1)
        // The checkpoint is untouched: the success it records really did happen.
        assertThat(garmin.lastSuccessfulCollectionAt).isEqualTo(successAt)
        assertThat(garmin.collectionAgeHours).isNotNull()
    }

    /**
     * A source with no attempt ledger must say so. Reporting `lastAttemptOutcome = null` without
     * `collectionTracked = false` would read as "never attempted", which is a claim this report
     * cannot make about a manual import.
     */
    @Test
    fun `sources that keep no attempt ledger are marked untracked rather than never-attempted`() {
        val response = service.freshness(date, 28)

        assertThat(sourceOf(response, DataSource.GARMIN_ACTIVITY).collectionTracked).isTrue()
        assertThat(sourceOf(response, DataSource.GARMIN_RECOVERY).collectionTracked).isTrue()
        listOf(DataSource.INTERVALS_FITNESS, DataSource.INTERVALS_ACTIVITY, DataSource.STRAVA_ACTIVITY).forEach {
            val source = sourceOf(response, it)
            assertThat(source.collectionTracked).isFalse()
            assertThat(source.lastAttemptOutcome).isNull()
            assertThat(source.lastFailureCode).isNull()
        }
    }

    /** Before anything has run at all, every field is an honest null rather than a zero or a date. */
    @Test
    fun `an untouched installation reports nulls, never zeroes standing in for data`() {
        val garmin = sourceOf(service.freshness(date, 28), DataSource.GARMIN_ACTIVITY)

        assertThat(garmin.latestDataDate).isNull()
        assertThat(garmin.dataAgeDays).isNull()
        assertThat(garmin.lastAttemptAt).isNull()
        assertThat(garmin.lastAttemptOutcome).isNull()
        assertThat(garmin.lastSuccessfulCollectionAt).isNull()
        assertThat(garmin.storedRecordCount).isZero()
    }

    /** The recovery block is the existing coverage report, so the two can never disagree. */
    @Test
    fun `the recovery block carries the per-day coverage for the window`() {
        val response = service.freshness(date, 7)

        assertThat(response.windowStartDate).isEqualTo(date.minusDays(6))
        assertThat(response.recovery.totalDays()).isEqualTo(7)
        assertThat(response.recovery.days()).hasSize(7)
        assertThat(response.recovery.days().first().date()).isEqualTo(date.minusDays(6))
    }

    /** An absurd window is clamped rather than rejected: this is a status read, not a command. */
    @Test
    fun `the window is clamped to a sane range`() {
        assertThat(service.freshness(date, 0).windowDays).isEqualTo(1)
        assertThat(service.freshness(date, 10_000).windowDays).isEqualTo(DataFreshnessService.MAX_WINDOW_DAYS)
        assertThat(service.freshness(date, null).windowDays).isEqualTo(DataFreshnessService.DEFAULT_WINDOW_DAYS)
    }

    /** Analysis completeness is counted over running activities only, and reported as plain counts. */
    @Test
    fun `analysis counts cover the window's running activities`() {
        storeActivity(ExternalSource.GARMIN, "g-1", date.minusDays(1))
        storeActivity(ExternalSource.GARMIN, "g-2", date.minusDays(2))

        val analysis = service.freshness(date, 28).analysis

        assertThat(analysis.runningActivityCount).isEqualTo(2)
        assertThat(analysis.analysisCurrent).isZero()
        assertThat(analysis.analysisMissingOrOutdated).isEqualTo(2)
    }

    @Test
    fun `an unparseable date is a bad request`() {
        mockMvc.perform(get("/api/v1/data-freshness").param("date", "not-a-date"))
            .andExpect(status().isBadRequest)
    }
}
