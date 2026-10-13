package com.runningai.analysis

import com.runningai.activity.Activity
import com.runningai.activity.ActivityType
import com.runningai.activity.ExternalSource
import com.runningai.activity.detail.DetailPartRecord
import com.runningai.activity.detail.DetailPartStatus
import com.runningai.activity.detail.DetailPayloadType
import com.runningai.activity.detail.SampleCompleteness
import com.runningai.activity.detail.SampleStreamFidelity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The reuse policy (Phase 7E-1) in isolation.
 *
 * The regression these guard: before this policy existed, "is the stored analysis current?" was a
 * comparison against one version string, so the pre-V22 analyses of 7 real activities counted as
 * current forever and no number of backfill runs could repair them.
 */
class AnalysisVersioningTest {

    private val attemptedAt: Instant = Instant.parse("2026-10-13T00:00:00Z")

    private fun activity(
        type: ActivityType = ActivityType.RUN,
        duration: Int = 1800,
        distance: Double? = 5000.0,
    ) = Activity(1L, ExternalSource.GARMIN, "g-1", type, Instant.parse("2026-09-18T02:10:45Z"), duration, distance, 150, 175)

    private fun parts(streamRows: Int = 2784, completeness: SampleCompleteness = SampleCompleteness.FULL) = listOf(
        DetailPartRecord(DetailPayloadType.ACTIVITY_DETAIL, DetailPartStatus.NORMALIZED, null, 1, attemptedAt),
        DetailPartRecord(DetailPayloadType.SPLITS, DetailPartStatus.NORMALIZED, null, 12, attemptedAt),
        DetailPartRecord(
            DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.NORMALIZED, null, streamRows, attemptedAt,
            SampleStreamFidelity(completeness, 20000, streamRows, streamRows),
        ),
    )

    private fun stored(
        version: String = RUNNING_ANALYSIS_VERSION,
        revision: Int = RUNNING_ANALYSIS_ENGINE_REVISION,
        digest: String?,
    ) = ActivityAnalysis(
        activityId = 1L,
        analysisVersion = version,
        analysisEngineRevision = revision,
        inputDigest = digest,
        status = AnalysisStatus.COMPLETE,
        inputSampleCompleteness = SampleCompleteness.FULL,
        computedAt = attemptedAt,
        session = SessionMetrics(validSampleCount = 10),
        zones = ZoneExposure(),
        threshold = ThresholdExposure(),
        laps = LapMetrics(),
        intervalGroups = emptyList(),
    )

    @Test
    fun `an unchanged activity analysed by the current engine is reused`() {
        val digest = AnalysisInputFingerprint.of(activity(), parts(), 170)

        assertThat(AnalysisReusePolicy.decide(stored(digest = digest), digest))
            .isEqualTo(AnalysisReuseDecision.Reuse)
    }

    @Test
    fun `an analysis from an older engine revision is re-analysed even when nothing else changed`() {
        val digest = AnalysisInputFingerprint.of(activity(), parts(), 170)

        // Exactly the Phase 7E-0 case: same contract string, same inputs, older engine.
        val decision = AnalysisReusePolicy.decide(stored(revision = 1, digest = digest), digest)

        assertThat(decision).isEqualTo(AnalysisReuseDecision.Reanalyse(ReanalysisReason.ENGINE_REVISION_CHANGED))
    }

    @Test
    fun `a row migrated by V24 - revision 0, no digest - is a re-analysis target`() {
        val digest = AnalysisInputFingerprint.of(activity(), parts(), 170)

        assertThat(AnalysisReusePolicy.decide(stored(revision = 0, digest = null), digest))
            .isEqualTo(AnalysisReuseDecision.Reanalyse(ReanalysisReason.ENGINE_REVISION_CHANGED))
    }

    @Test
    fun `a current-revision row without an input digest is never reused`() {
        val digest = AnalysisInputFingerprint.of(activity(), parts(), 170)

        assertThat(AnalysisReusePolicy.decide(stored(digest = null), digest))
            .isEqualTo(AnalysisReuseDecision.Reanalyse(ReanalysisReason.INPUT_DIGEST_UNKNOWN))
    }

    @Test
    fun `a different output contract is a re-analysis target`() {
        val digest = AnalysisInputFingerprint.of(activity(), parts(), 170)

        assertThat(AnalysisReusePolicy.decide(stored(version = "RUNNING_ANALYSIS_V0", digest = digest), digest))
            .isEqualTo(AnalysisReuseDecision.Reanalyse(ReanalysisReason.CONTRACT_CHANGED))
    }

    @Test
    fun `nothing stored is a re-analysis target`() {
        assertThat(AnalysisReusePolicy.decide(null, "whatever"))
            .isEqualTo(AnalysisReuseDecision.Reanalyse(ReanalysisReason.NO_STORED_ANALYSIS))
    }

    @Test
    fun `isCurrent is false for an older revision and true for the current one`() {
        assertThat(AnalysisReusePolicy.isCurrent(stored(revision = 1, digest = "d"))).isFalse()
        assertThat(AnalysisReusePolicy.isCurrent(stored(revision = 0, digest = null))).isFalse()
        assertThat(AnalysisReusePolicy.isCurrent(stored(digest = "d"))).isTrue()
        assertThat(AnalysisReusePolicy.isCurrent(null)).isFalse()
    }

    @Test
    fun `the digest is stable for identical inputs and changes with each input that matters`() {
        val base = AnalysisInputFingerprint.of(activity(), parts(), 170)

        assertThat(AnalysisInputFingerprint.of(activity(), parts(), 170)).isEqualTo(base)
        assertThat(AnalysisInputFingerprint.of(activity(duration = 1801), parts(), 170)).isNotEqualTo(base)
        assertThat(AnalysisInputFingerprint.of(activity(distance = 5001.0), parts(), 170)).isNotEqualTo(base)
        assertThat(AnalysisInputFingerprint.of(activity(type = ActivityType.TREADMILL_RUN), parts(), 170)).isNotEqualTo(base)
        // a stream re-collected at a higher fidelity: more rows, different completeness
        assertThat(AnalysisInputFingerprint.of(activity(), parts(streamRows = 1399, completeness = SampleCompleteness.DOWNSAMPLED), 170))
            .isNotEqualTo(base)
        assertThat(AnalysisInputFingerprint.of(activity(), parts(), 172)).isNotEqualTo(base)
        assertThat(AnalysisInputFingerprint.of(activity(), parts(), null)).isNotEqualTo(base)
    }

    @Test
    fun `re-recording the same parts at a later time is not an input change`() {
        val later = listOf(
            DetailPartRecord(DetailPayloadType.ACTIVITY_DETAIL, DetailPartStatus.NORMALIZED, null, 1, attemptedAt.plusSeconds(86_400)),
            DetailPartRecord(DetailPayloadType.SPLITS, DetailPartStatus.NORMALIZED, null, 12, attemptedAt.plusSeconds(86_400)),
            DetailPartRecord(
                DetailPayloadType.ACTIVITY_DETAILS_STREAM, DetailPartStatus.NORMALIZED, null, 2784, attemptedAt.plusSeconds(86_400),
                SampleStreamFidelity(SampleCompleteness.FULL, 20000, 2784, 2784),
            ),
        )

        assertThat(AnalysisInputFingerprint.of(activity(), later, 170))
            .isEqualTo(AnalysisInputFingerprint.of(activity(), parts(), 170))
    }

    @Test
    fun `part order does not change the digest`() {
        assertThat(AnalysisInputFingerprint.of(activity(), parts().reversed(), 170))
            .isEqualTo(AnalysisInputFingerprint.of(activity(), parts(), 170))
    }
}
