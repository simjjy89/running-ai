package com.runningai.draftpublish

import com.runningai.RunningAiApplication
import com.runningai.integration.intervals.WorkoutPublishProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.assertThatNoException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

/**
 * Legacy publishing XOR AI draft publishing (Phase 6G.1): both switches on must stop the
 * application from starting; every other combination starts normally. No publish is ever attempted.
 */
class PublishingModeGuardTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WorkoutPublishProperties::class, DraftPublishingProperties::class)
    @Import(PublishingModeGuard::class)
    class GuardOnly

    private val runner = ApplicationContextRunner().withUserConfiguration(GuardOnly::class.java)

    private fun withSwitches(legacy: Boolean, draft: Boolean) = runner.withPropertyValues(
        "running-ai.workout-publishing.enabled=$legacy",
        "running-ai.draft-publishing.enabled=$draft",
    )

    @Nested
    inner class PureCheck {

        @Test
        fun `only both switches on is rejected`() {
            assertThatNoException().isThrownBy { PublishingModeGuard.check(legacyEnabled = false, draftEnabled = false) }
            assertThatNoException().isThrownBy { PublishingModeGuard.check(legacyEnabled = true, draftEnabled = false) }
            assertThatNoException().isThrownBy { PublishingModeGuard.check(legacyEnabled = false, draftEnabled = true) }
            assertThatThrownBy { PublishingModeGuard.check(legacyEnabled = true, draftEnabled = true) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Legacy workout publishing and AI draft publishing cannot be enabled at the same time")
        }
    }

    @Nested
    inner class ContextStartup {

        @Test
        fun `both off starts`() {
            withSwitches(legacy = false, draft = false).run { assertThat(it).hasNotFailed().hasSingleBean(PublishingModeGuard::class.java) }
        }

        @Test
        fun `legacy only starts`() {
            withSwitches(legacy = true, draft = false).run { assertThat(it).hasNotFailed().hasSingleBean(PublishingModeGuard::class.java) }
        }

        @Test
        fun `draft only starts`() {
            withSwitches(legacy = false, draft = true).run { assertThat(it).hasNotFailed().hasSingleBean(PublishingModeGuard::class.java) }
        }

        @Test
        fun `both on fails at startup with an explicit message`() {
            withSwitches(legacy = true, draft = true).run {
                assertThat(it).hasFailed()
                assertThat(it.startupFailure).rootCause()
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessage(PublishingModeGuard.MESSAGE)
            }
        }

        @Test
        fun `with nothing configured both switches and the legacy scheduler default to off`() {
            runner.run {
                assertThat(it).hasNotFailed()
                assertThat(it.getBean(DraftPublishingProperties::class.java).enabled).isFalse()
                val legacy = it.getBean(WorkoutPublishProperties::class.java)
                assertThat(legacy.enabled()).isFalse()
                assertThat(legacy.scheduler().enabled()).isFalse()
            }
        }
    }

    /**
     * The real application, configured through the real environment-variable names that
     * application.yml resolves, refuses to start. No web server; nothing is published (the context
     * never finishes starting, and the Intervals API key is blank).
     */
    @Test
    fun `the real application refuses to start with both environment switches on`() {
        assertThatThrownBy {
            SpringApplicationBuilder(RunningAiApplication::class.java)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .properties(
                    "WORKOUT_PUBLISHING_ENABLED=true",
                    "RUNNING_AI_DRAFT_PUBLISHING_ENABLED=true",
                    "running-ai.intervals.api-key=",
                    "running-ai.intervals.base-url=http://127.0.0.1:9",
                )
                .run().close()
        }.rootCause()
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage(PublishingModeGuard.MESSAGE)
    }
}
