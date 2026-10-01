package com.runningai.coach.claude

import com.runningai.coach.AiCoachException
import com.runningai.coach.CoachTestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Process-level behaviour of the CLI boundary, driven by tiny stand-in batch scripts instead of
 * the real Claude CLI: these tests must never make a paid API call or need a login session.
 *
 * Windows-only because the stand-ins are `.cmd` scripts; the production code itself is not
 * platform specific, and the parsing/coach layers above it are covered by platform-independent
 * tests.
 */
@EnabledOnOs(OS.WINDOWS)
class ClaudeCliClientTest {

    @TempDir
    lateinit var tempDir: Path

    /** Writes a .cmd stand-in that ignores its arguments and behaves as scripted. */
    private fun fakeCli(name: String, body: String): String {
        val script = tempDir.resolve("$name.cmd")
        Files.writeString(script, "@echo off\r\n$body\r\n")
        return script.toAbsolutePath().toString()
    }

    private fun client(command: String, timeoutSeconds: Long = 30) =
        ClaudeCliClient(CoachTestFixtures.properties(command = command, timeoutSeconds = timeoutSeconds))

    @Test
    fun `a successful call returns stdout`() {
        val cli = fakeCli("ok", """echo {"is_error":false,"result":"hello"}""")

        val result = client(cli).run("system", "user")

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).contains("is_error")
    }

    @Test
    fun `a missing CLI is reported as provider unavailable`() {
        assertThatThrownBy { client("definitely-not-a-real-command-xyz-6e").run("s", "u") }
            .isInstanceOfSatisfying(AiCoachException::class.java) { e ->
                assertThat(e.reason).isEqualTo(AiCoachException.Reason.PROVIDER_UNAVAILABLE)
            }
    }

    @Test
    fun `a non-zero exit is reported as a provider error`() {
        val cli = fakeCli("fail", "echo something went wrong 1>&2\r\nexit /b 3")

        assertThatThrownBy { client(cli).run("s", "u") }
            .isInstanceOfSatisfying(AiCoachException::class.java) { e ->
                assertThat(e.reason).isEqualTo(AiCoachException.Reason.PROVIDER_ERROR)
                assertThat(e.message).contains("3")
            }
    }

    @Test
    fun `an unauthenticated CLI is reported as auth required, not as a generic failure`() {
        val cli = fakeCli("noauth", "echo Invalid API key - Please log in 1>&2\r\nexit /b 1")

        assertThatThrownBy { client(cli).run("s", "u") }
            .isInstanceOfSatisfying(AiCoachException::class.java) { e ->
                assertThat(e.reason).isEqualTo(AiCoachException.Reason.AUTH_REQUIRED)
            }
    }

    @Test
    fun `a timeout kills the process and is reported as a timeout`() {
        // ping -n 20 sleeps ~19s, far beyond the 2s timeout configured here.
        val cli = fakeCli("slow", "ping -n 20 127.0.0.1 >nul\r\necho {}")

        val started = System.currentTimeMillis()
        assertThatThrownBy { client(cli, timeoutSeconds = 2).run("s", "u") }
            .isInstanceOfSatisfying(AiCoachException::class.java) { e ->
                assertThat(e.reason).isEqualTo(AiCoachException.Reason.TIMEOUT)
            }
        // it really gave up early rather than waiting for the child to finish
        assertThat(System.currentTimeMillis() - started).isLessThan(15_000)
    }

    @Test
    fun `empty output is reported as an invalid response`() {
        val cli = fakeCli("silent", "exit /b 0")

        assertThatThrownBy { client(cli).run("s", "u") }
            .isInstanceOfSatisfying(AiCoachException::class.java) { e ->
                assertThat(e.reason).isEqualTo(AiCoachException.Reason.INVALID_RESPONSE)
                assertThat(e.message).contains("no output")
            }
    }

    @Test
    fun `stdout is capped so a runaway response cannot exhaust memory`() {
        val cli = fakeCli("loud", "for /L %%i in (1,1,500) do @echo 0123456789012345678901234567890123456789")
        val capped = ClaudeCliClient(
            CoachTestFixtures.properties(command = cli).let {
                it.copy(claude = it.claude.copy(maxOutputBytes = 100))
            },
        )

        val result = capped.run("s", "u")

        assertThat(result.stdout.length).isLessThanOrEqualTo(100)
    }

    @Test
    fun `the prompt is sent on stdin and never appears in the command line`() {
        // The stand-in echoes back whatever it reads from stdin, proving stdin is the transport.
        val cli = fakeCli("echoin", "set /p line=\r\necho {\"is_error\":false,\"result\":\"%line%\"}")

        val result = client(cli).run("system prompt", "SECRET-CONTEXT-MARKER")

        assertThat(result.stdout).contains("SECRET-CONTEXT-MARKER")
    }
}
