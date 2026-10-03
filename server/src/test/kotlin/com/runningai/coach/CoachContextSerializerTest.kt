package com.runningai.coach

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Determinism, forbidden fields and the size guard (Phase 6H-7 §40-42, §70, §105). Pure, no Spring.
 */
class CoachContextSerializerTest {

    private val builtAt: Instant = Instant.parse("2026-10-03T00:00:00Z")

    private fun v2(date: LocalDate = LocalDate.of(2026, 10, 3)) = TrainingContextV2(
        date = date,
        athlete = AthleteThresholds(170, 300),
        dataCoverage = DataCoverageV2(90, 1, 1, 1, 1, 90, 1, 28, 1, date, date),
        recovery = RecoveryContext(),
        trainingLoad = TrainingLoadContextV2(date, 0, 20.0, 18.0, 2.0, 0.1, 10.0, 10.0, null, null),
        trainingRhythm = TrainingRhythmV2(1, 0, date, 0, null, null, true, null, null),
        recentActivities = listOf(
            RecentActivityEvidence(
                activity = RecentActivityFacts(date, com.runningai.activity.ActivityType.RUN, 1800, 6000.0, 150, 170, 170.0, null),
                runningAiAnalysis = RunningAiAnalysisEvidence(3.0, -2.0, 4.2, -1.0, 300.0, 50.0, 10.0, 2.5,
                    mapOf(1 to 10.0, 2 to 60.0, 3 to 30.0), 1800.0, emptyList()),
                intervals = IntervalsActivityEvidence(50, 80.0, 30.0, 25.0),
                dataQuality = ActivityDataQuality(com.runningai.activity.detail.SampleCompleteness.FULL,
                    com.runningai.analysis.AnalysisStatus.COMPLETE, "RUNNING_ANALYSIS_V1"),
            ),
        ),
        constraints = SessionConstraints(availableMinutes = 40),
    )

    private fun serializer(maxBytes: Int = 65_536) = CoachContextSerializer(TrainingContextV2Properties(maxSnapshotBytes = maxBytes))

    @Test
    fun `the same context serializes to byte-identical JSON every time`() {
        val a = serializer().snapshot(v2(), builtAt)
        val b = serializer().snapshot(v2(), builtAt)

        assertThat(a.json).isEqualTo(b.json)
        assertThat(a.sha256).isEqualTo(b.sha256)
    }

    @Test
    fun `changing one metric changes the hash`() {
        val base = serializer().snapshot(v2(), builtAt)
        val changed = serializer().snapshot(v2().copy(trainingLoad = v2().trainingLoad.copy(ctl = 21.0)), builtAt)

        assertThat(changed.sha256).isNotEqualTo(base.sha256)
    }

    @Test
    fun `a V1 context is tagged V1 and a V2 context is tagged V2`() {
        val v1Context = TrainingContext(
            date = LocalDate.of(2026, 10, 3), athlete = AthleteThresholds(170, 300),
            recentTraining = CoachTestFixtures.recentTraining(),
            recovery = RecoveryContext(), weeklyContext = CoachTestFixtures.weeklyContext(),
            constraints = SessionConstraints(),
        )
        assertThat(serializer().snapshot(v1Context, builtAt).version).isEqualTo(ContextVersion.V1)
        assertThat(serializer().snapshot(v2(), builtAt).version).isEqualTo(ContextVersion.V2)
    }

    @Test
    fun `an oversized context is rejected before anything is sent anywhere`() {
        assertThatThrownBy { serializer(maxBytes = 10).snapshot(v2(), builtAt) }
            .isInstanceOf(TrainingContextTooLargeException::class.java)
            .extracting { (it as TrainingContextTooLargeException).version }
            .isEqualTo(ContextVersion.V2)
    }

    @Test
    fun `a well-formed V2 context stays comfortably under the default 64 KiB guard`() {
        val snapshot = serializer().snapshot(v2(), builtAt)
        assertThat(snapshot.json.toByteArray(Charsets.UTF_8).size).isLessThan(65_536)
    }

    @Test
    fun `no forbidden identity, GPS or credential field appears in the serialized snapshot`() {
        val json = serializer().snapshot(v2(), builtAt).json

        listOf(
            "externalActivityId", "garminActivityId", "intervalsActivityId", "externalId", "externalSource",
            "latitude", "longitude", "API_KEY", "Authorization", "apiKey",
        ).forEach { forbidden ->
            assertThat(json).doesNotContainIgnoringCase(forbidden)
        }
    }

    @Test
    fun `there is no candidateTrainingTypes field in the V2 snapshot`() {
        val json = serializer().snapshot(v2(), builtAt).json
        assertThat(json).doesNotContainIgnoringCase("candidateTrainingTypes")
    }
}
