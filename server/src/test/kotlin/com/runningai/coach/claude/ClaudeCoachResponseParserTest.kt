package com.runningai.coach.claude

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.runningai.coach.CoachTestFixtures
import com.runningai.coach.WorkoutDraft
import com.runningai.coach.AiCoachException
import com.runningai.coach.CoachProvider
import com.runningai.coach.CoachTestFixtures.DATE
import com.runningai.training.IntensityClass
import com.runningai.training.PrimaryTargetType
import com.runningai.training.SegmentType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Parsing of the two JSON layers. No process, no network; all payloads are synthetic. */
class ClaudeCoachResponseParserTest {

    private val parser = ClaudeCoachResponseParser(ObjectMapper())

    private val coachJson = """
        {"assessment":{"recovery":"Unknown - no recovery data","loadTrend":"Steady",
         "selectedWorkoutType":"EASY","rationale":"Keep it easy today.","warnings":[]},
         "workout":{"title":"Easy Run","totalDurationMinutes":40,"segments":[
           {"type":"WARM_UP","durationMinutes":10,"intensity":"VERY_EASY"},
           {"type":"MAIN","durationMinutes":25,"intensity":"EASY","paceSecondsPerKmFast":330,
            "paceSecondsPerKmSlow":360},
           {"type":"COOL_DOWN","durationMinutes":5,"intensity":"VERY_EASY"}]}}
    """.trimIndent()

    /** Wraps coach JSON the way the Claude CLI's `--output-format json` envelope does. */
    private fun envelope(result: String, isError: Boolean = false, subtype: String = "success"): String =
        ObjectMapper().writeValueAsString(
            mapOf(
                "is_error" to isError,
                "subtype" to subtype,
                "result" to result,
                "modelUsage" to mapOf("claude-sonnet-5" to mapOf("inputTokens" to 100)),
            ),
        )

    private fun parse(stdout: String) = parser.parse(stdout, DATE, "test-model", 1)

    private fun expectInvalid(stdout: String, vararg contains: String) {
        assertThatThrownBy { parse(stdout) }
            .isInstanceOfSatisfying(AiCoachException::class.java) { e ->
                assertThat(e.reason).isEqualTo(AiCoachException.Reason.INVALID_RESPONSE)
                contains.forEach { assertThat(e.message).contains(it) }
            }
    }

    @Test
    fun `parses a well formed response`() {
        val draft = parse(envelope(coachJson))

        assertThat(draft.title).isEqualTo("Easy Run")
        assertThat(draft.workoutType).isEqualTo("EASY")
        assertThat(draft.totalDurationMinutes).isEqualTo(40)
        assertThat(draft.date).isEqualTo(DATE)
        assertThat(draft.version).isEqualTo(1)
        assertThat(draft.provider).isEqualTo(CoachProvider.CLAUDE)
        assertThat(draft.assessment.rationale).isEqualTo("Keep it easy today.")
        assertThat(draft.segments).hasSize(3)
        assertThat(draft.segments[0].type).isEqualTo(SegmentType.WARM_UP)
        assertThat(draft.segments[1].intensity).isEqualTo(IntensityClass.EASY)
        assertThat(draft.segments[1].paceSecondsPerKmFast).isEqualTo(330)
        assertThat(draft.segments[2].paceSecondsPerKmFast).isNull()
    }

    @Test
    fun `prefers the CLI structured_output when present`() {
        val structured = ObjectMapper().writeValueAsString(
            mapOf(
                "is_error" to false,
                "result" to "ignored because structured_output wins",
                "structured_output" to ObjectMapper().readTree(coachJson),
            ),
        )

        assertThat(parse(structured).title).isEqualTo("Easy Run")
    }

    @Test
    fun `strips a markdown fence defensively even though the contract forbids it`() {
        val fenced = parse(envelope("```json\n$coachJson\n```"))

        assertThat(fenced.title).isEqualTo("Easy Run")
    }

    @Test
    fun `an is_error envelope becomes a provider error`() {
        assertThatThrownBy { parse(envelope(coachJson, isError = true, subtype = "error_max_turns")) }
            .isInstanceOfSatisfying(AiCoachException::class.java) { e ->
                assertThat(e.reason).isEqualTo(AiCoachException.Reason.PROVIDER_ERROR)
                assertThat(e.message).contains("error_max_turns")
            }
    }

    @Test
    fun `malformed outer JSON is rejected`() {
        expectInvalid("this is not json at all", "not valid JSON")
    }

    @Test
    fun `an outer JSON array is rejected`() {
        expectInvalid("[1,2,3]", "not a JSON object")
    }

    @Test
    fun `malformed inner JSON is rejected`() {
        expectInvalid(envelope("{ this is not valid json"), "coach response was not valid JSON")
    }

    @Test
    fun `an empty result is rejected`() {
        expectInvalid(envelope("   "), "no result text")
    }

    @Test
    fun `a missing assessment is rejected`() {
        expectInvalid(envelope("""{"workout":{"title":"x","totalDurationMinutes":30,"segments":[]}}"""),
            "assessment is missing")
    }

    @Test
    fun `a missing workout is rejected`() {
        val onlyAssessment = """{"assessment":{"recovery":"a","loadTrend":"b",
            "selectedWorkoutType":"EASY","rationale":"c"}}"""
        expectInvalid(envelope(onlyAssessment), "workout is missing")
    }

    @Test
    fun `a missing required assessment field is rejected rather than defaulted`() {
        val noRationale = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY"},
            "workout":{"title":"x","totalDurationMinutes":30,"segments":[]}}"""
        expectInvalid(envelope(noRationale), "rationale is missing")
    }

    @Test
    fun `a missing segments array is rejected`() {
        val noSegments = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30}}"""
        expectInvalid(envelope(noSegments), "segments is missing")
    }

    @Test
    fun `an unknown segment type is rejected rather than defaulted`() {
        val badType = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30,"segments":[
            {"type":"SPRINT_FLOAT","durationMinutes":30,"intensity":"EASY"}]}}"""
        expectInvalid(envelope(badType), "SPRINT_FLOAT", "WARM_UP")
    }

    @Test
    fun `an unknown intensity is rejected rather than defaulted`() {
        val badIntensity = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30,"segments":[
            {"type":"MAIN","durationMinutes":30,"intensity":"BRUTAL"}]}}"""
        expectInvalid(envelope(badIntensity), "BRUTAL")
    }

    @Test
    fun `a non numeric duration is rejected`() {
        val badDuration = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":"thirty","segments":[
            {"type":"MAIN","durationMinutes":30,"intensity":"EASY"}]}}"""
        expectInvalid(envelope(badDuration), "totalDurationMinutes is missing or is not an integer")
    }

    @Test
    fun `an explicit null optional target stays null instead of failing`() {
        val nulls = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30,"segments":[
            {"type":"MAIN","durationMinutes":30,"intensity":"EASY","paceSecondsPerKmFast":null,
             "treadmillSpeedKphMin":null}]}}"""

        val draft = parse(envelope(nulls))

        assertThat(draft.segments[0].paceSecondsPerKmFast).isNull()
        assertThat(draft.segments[0].treadmillSpeedKphMin).isNull()
    }

    @Test
    fun `enum values are accepted case insensitively`() {
        val lower = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30,"segments":[
            {"type":"main","durationMinutes":30,"intensity":"easy"}]}}"""

        assertThat(parse(envelope(lower)).segments[0].type).isEqualTo(SegmentType.MAIN)
    }

    @Test
    fun `the version passed in is used, so a revision is version 2`() {
        assertThat(parser.parse(envelope(coachJson), DATE, "m", 2).version).isEqualTo(2)
    }

    @Test
    fun `the model falls back to the envelope when none is supplied`() {
        assertThat(parser.parse(envelope(coachJson), DATE, null, 1).model).isEqualTo("claude-sonnet-5")
    }

    // ---- REST (Phase 6F.1) --------------------------------------------------------------------

    private val restJson = """
        {"assessment":{"recovery":"Athlete reports exhaustion","loadTrend":"High",
         "selectedWorkoutType":"REST","rationale":"Take a full rest day.","warnings":["Rest fully"]},
         "workout":{"title":"Rest Day","totalDurationMinutes":0,"segments":[]}}
    """.trimIndent()

    @Test
    fun `a rest day with an empty segments array parses as a REST draft`() {
        val draft = parse(envelope(restJson))

        assertThat(draft.workoutType).isEqualTo("REST")
        assertThat(draft.isRest).isTrue()
        assertThat(draft.totalDurationMinutes).isZero()
        assertThat(draft.segments).isEmpty()
        assertThat(draft.assessment.rationale).isEqualTo("Take a full rest day.")
    }

    @Test
    fun `a rest day still needs the segments array to be present`() {
        expectInvalid(envelope(restJson.replace(",\"segments\":[]", "")), "segments")
    }

    @Test
    fun `a REST draft round-trips through JSON without a derived flag leaking`() {
        val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val rest = CoachTestFixtures.restDraft()

        val json = mapper.writeValueAsString(rest)
        val back = mapper.readValue(json, WorkoutDraft::class.java)

        assertThat(json).contains("\"workoutType\":\"REST\"", "\"totalDurationMinutes\":0", "\"segments\":[]")
        assertThat(json).doesNotContain("isRest").doesNotContain("\"rest\"")
        assertThat(back).isEqualTo(rest)
        assertThat(back.isRest).isTrue()
    }

    // ---- target completeness fields (Phase 6H-7.1) ---------------------------------------------

    @Test
    fun `parses primaryTargetType, percent-LTHR and a nested recovery object`() {
        val json = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"THRESHOLD",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":21,"segments":[
            {"type":"MAIN","durationMinutes":5,"intensity":"HARD","primaryTargetType":"PACE",
             "paceSecondsPerKmFast":285,"paceSecondsPerKmSlow":300,"repetitions":3,
             "recovery":{"durationMinutes":2,"intensity":"VERY_EASY","primaryTargetType":"HEART_RATE",
                         "description":"Easy jog","heartRatePercentLthrMin":65,"heartRatePercentLthrMax":75}}
            ]}}"""

        val segment = parse(envelope(json)).segments.single()

        assertThat(segment.primaryTargetType).isEqualTo(PrimaryTargetType.PACE)
        assertThat(segment.paceSecondsPerKmFast).isEqualTo(285)
        assertThat(segment.recovery).isNotNull()
        assertThat(segment.recovery!!.durationMinutes).isEqualTo(2)
        assertThat(segment.recovery!!.primaryTargetType).isEqualTo(PrimaryTargetType.HEART_RATE)
        assertThat(segment.recovery!!.heartRatePercentLthrMin).isEqualTo(65)
        assertThat(segment.recovery!!.heartRatePercentLthrMax).isEqualTo(75)
        assertThat(segment.recovery!!.description).isEqualTo("Easy jog")
        assertThat(segment.recoveryDurationMinutes).isNull()
    }

    @Test
    fun `the old shape without any new field still parses exactly as before`() {
        // Draft #7's actual published shape: no primaryTargetType, no %LTHR, recoveryDurationMinutes only.
        val legacy = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"THRESHOLD",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":21,"segments":[
            {"type":"MAIN","durationMinutes":5,"intensity":"HARD","paceSecondsPerKmFast":285,
             "paceSecondsPerKmSlow":300,"repetitions":3,"recoveryDurationMinutes":2}]}}"""

        val segment = parse(envelope(legacy)).segments.single()

        assertThat(segment.primaryTargetType).isNull()
        assertThat(segment.heartRatePercentLthrMin).isNull()
        assertThat(segment.recovery).isNull()
        assertThat(segment.recoveryDurationMinutes).isEqualTo(2)
    }

    @Test
    fun `an unknown primaryTargetType is rejected rather than defaulted`() {
        val bad = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30,"segments":[
            {"type":"MAIN","durationMinutes":30,"intensity":"EASY","primaryTargetType":"ABSOLUTE_BPM"}]}}"""

        expectInvalid(bad.let { envelope(it) }, "ABSOLUTE_BPM")
    }

    @Test
    fun `a recovery that is not an object is rejected`() {
        val bad = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30,"segments":[
            {"type":"MAIN","durationMinutes":30,"intensity":"EASY","repetitions":2,"recovery":"2 minutes"}]}}"""

        expectInvalid(envelope(bad), "recovery is not an object")
    }

    @Test
    fun `a recovery missing its required primaryTargetType is rejected`() {
        val bad = """{"assessment":{"recovery":"a","loadTrend":"b","selectedWorkoutType":"EASY",
            "rationale":"c"},"workout":{"title":"x","totalDurationMinutes":30,"segments":[
            {"type":"MAIN","durationMinutes":30,"intensity":"EASY","repetitions":2,
             "recovery":{"durationMinutes":2,"intensity":"VERY_EASY"}}]}}"""

        expectInvalid(envelope(bad), "recovery.primaryTargetType is missing")
    }
}
