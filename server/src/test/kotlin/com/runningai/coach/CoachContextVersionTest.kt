package com.runningai.coach

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.test.context.ActiveProfiles

/** `running-ai.coach.context-version` (Phase 6H-7): which [CoachTrainingContext] gets built. */
class CoachContextVersionTest {

    @Nested
    @SpringBootTest(properties = ["running-ai.intervals.api-key=", "running-ai.intervals.base-url=http://127.0.0.1:9"])
    @ActiveProfiles("test")
    inner class Unset {

        @Autowired private lateinit var properties: CoachProperties

        @Test
        fun `nothing configured means V1`() {
            assertThat(properties.contextVersion).isEqualTo(ContextVersion.V1)
        }
    }

    @Nested
    @SpringBootTest(
        properties = [
            "running-ai.coach.context-version=V1",
            "running-ai.intervals.api-key=",
            "running-ai.intervals.base-url=http://127.0.0.1:9",
        ],
    )
    @ActiveProfiles("test")
    inner class ExplicitV1 {

        @Autowired private lateinit var properties: CoachProperties
        @Autowired private lateinit var router: CoachTrainingContextBuilder

        @Test
        fun `V1 selects the V1 builder`() {
            assertThat(properties.contextVersion).isEqualTo(ContextVersion.V1)
            assertThat(router.build(router.today())).isInstanceOf(TrainingContext::class.java)
        }
    }

    @Nested
    @SpringBootTest(
        properties = [
            "running-ai.coach.context-version=V2",
            "running-ai.intervals.api-key=",
            "running-ai.intervals.base-url=http://127.0.0.1:9",
        ],
    )
    @ActiveProfiles("test")
    inner class ExplicitV2 {

        @Autowired private lateinit var properties: CoachProperties
        @Autowired private lateinit var router: CoachTrainingContextBuilder

        @Test
        fun `V2 selects the V2 builder`() {
            assertThat(properties.contextVersion).isEqualTo(ContextVersion.V2)
            assertThat(router.build(router.today())).isInstanceOf(TrainingContextV2::class.java)
        }
    }

    @Nested
    @SpringBootTest(
        properties = [
            "RUNNING_AI_TRAINING_CONTEXT_VERSION=V2",
            "running-ai.intervals.api-key=",
            "running-ai.intervals.base-url=http://127.0.0.1:9",
        ],
    )
    @ActiveProfiles("test")
    inner class ViaTheDocumentedEnvironmentVariable {

        @Autowired private lateinit var properties: CoachProperties

        /**
         * The operational on/off switch for this phase (§85-87): confirms
         * `application.yml`'s `context-version: ${RUNNING_AI_TRAINING_CONTEXT_VERSION:V1}` placeholder
         * actually resolves from that exact environment variable name, not just from the Spring
         * property path directly (which every other nested class here sets, bypassing the placeholder).
         */
        @Test
        fun `the exact documented environment variable name actually selects V2`() {
            assertThat(properties.contextVersion).isEqualTo(ContextVersion.V2)
        }
    }

    @Test
    fun `an unknown context version does not even bind`() {
        ApplicationContextRunner()
            .withPropertyValues("running-ai.coach.context-version=V3")
            .withUserConfiguration(CoachProperties::class.java)
            .run { context -> assertThat(context).hasFailed() }
    }
}
