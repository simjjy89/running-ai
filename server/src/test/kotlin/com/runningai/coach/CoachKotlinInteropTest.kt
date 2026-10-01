package com.runningai.coach

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.runningai.athlete.AthleteIntensityProfileResponse
import com.runningai.training.CandidateTrainingType
import com.runningai.training.IntensityClass
import com.runningai.training.SegmentType
import com.runningai.training.TargetAvailability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Proves the Java/Kotlin boundary works in both directions, which is the whole premise of adding
 * Kotlin to this previously Java-only module: new coach code is Kotlin, the existing domain stays
 * Java, and neither needs a translation layer.
 */
class CoachKotlinInteropTest {

    @Test
    fun `Kotlin reads Java records and enums from the existing training domain`() {
        val javaRecord = AthleteIntensityProfileResponse(true, 170, 300)

        // Java record accessors are callable as Kotlin methods.
        assertThat(javaRecord.lactateThresholdHeartRateBpm()).isEqualTo(170)
        assertThat(javaRecord.lactateThresholdPaceSecondsPerKm()).isEqualTo(300)
        assertThat(SegmentType.valueOf("WARM_UP")).isEqualTo(SegmentType.WARM_UP)
        assertThat(IntensityClass.entries).contains(IntensityClass.HARD)
        assertThat(TargetAvailability.entries).isNotEmpty
    }

    @Test
    fun `Kotlin coach types reuse the Java enums rather than duplicating them`() {
        val segment = CoachTestFixtures.segment(type = SegmentType.MAIN, intensity = IntensityClass.HARD)

        // The declared Kotlin property types ARE the Java enums.
        assertThat(segment.type).isInstanceOf(SegmentType::class.java)
        assertThat(segment.intensity).isInstanceOf(IntensityClass::class.java)
        assertThat(CoachTestFixtures.recentTraining().candidateTrainingTypes)
            .allMatch { it is CandidateTrainingType }
    }

    @Test
    fun `Java code can call into the Kotlin coach API`() {
        // Exercised from Java in CoachJavaInteropTest; here we assert the shapes Java relies on are
        // plain JVM types with no Kotlin-only signature (no default-argument-only constructors).
        val draftClass = WorkoutDraft::class.java
        val coachClass = AiCoach::class.java

        assertThat(draftClass.methods.map { it.name }).contains("getTitle", "getSegments", "getAssessment")
        assertThat(coachClass.methods.map { it.name }).containsExactlyInAnyOrder("createWorkout", "reviseWorkout")
    }

    @Test
    fun `Kotlin data classes serialize through Jackson with the Kotlin module`() {
        // Same module set Spring Boot auto-configures: Kotlin for data-class constructors, JSR-310
        // for the java.time fields the coach domain shares with the existing Java domain.
        val mapper = ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
        val draft = CoachTestFixtures.draft()

        val json = mapper.writeValueAsString(draft)
        val back = mapper.readValue(json, WorkoutDraft::class.java)

        assertThat(back.title).isEqualTo(draft.title)
        assertThat(back.segments).hasSameSizeAs(draft.segments)
        assertThat(back.assessment.rationale).isEqualTo(draft.assessment.rationale)
    }

    @Test
    fun `a repeated interval block computes its effective duration in Kotlin`() {
        val block = CoachTestFixtures.segment(durationMinutes = 3, repetitions = 5, recoveryMinutes = 2)

        assertThat(block.effectiveDurationMinutes).isEqualTo(25)
    }

    @Test
    fun `a plain segment contributes exactly its own duration`() {
        assertThat(CoachTestFixtures.segment(durationMinutes = 30).effectiveDurationMinutes).isEqualTo(30)
    }
}
