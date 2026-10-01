package com.runningai.coach.claude

import com.runningai.coach.AiCoachException
import com.runningai.coach.CoachProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Raw result of one CLI invocation, before any coach-level interpretation. */
data class ClaudeCliResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs the locally installed Claude Code CLI once, non-interactively, and returns what it printed.
 *
 * **Authentication is the CLI's own login session on this host.** No API key is read, stored,
 * passed or logged anywhere: `ANTHROPIC_API_KEY` is never set by this code, and `--bare` is
 * deliberately *not* used because it would force API-key authentication.
 *
 * The invocation is locked down so a coach call can only ever produce text:
 *  - `--tools ""` removes every built-in tool (no Bash, no file read/write, no WebFetch);
 *  - `--strict-mcp-config` ignores all configured MCP servers, so RunningAI's own
 *    `publish_workout` MCP tool is unreachable from here even when MCP is enabled;
 *  - `--setting-sources ""` ignores user/project/local settings, so local permission settings
 *    cannot widen the call;
 *  - `--no-session-persistence` writes nothing to the session store.
 *
 * The prompt goes in on **stdin**, not as an argument: a training context must never appear in a
 * process command line where any local process could read it via the process table.
 *
 * Exactly one attempt is made. There is no retry: a failed coach call is surfaced to the caller
 * rather than re-run, so a broken CLI or an auth problem can never turn into a loop of paid calls.
 */
@Component
class ClaudeCliClient(private val properties: CoachProperties) {

    private val log = LoggerFactory.getLogger(ClaudeCliClient::class.java)

    fun run(systemPrompt: String, userPrompt: String): ClaudeCliResult {
        val claude = properties.claude
        val command = listOf(
            claude.command,
            "-p",
            "--output-format", "json",
            "--model", claude.model,
            "--system-prompt", systemPrompt,
            "--tools", "",
            "--strict-mcp-config",
            "--setting-sources", "",
            "--no-session-persistence",
        )

        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (e: IOException) {
            throw AiCoachException(
                AiCoachException.Reason.PROVIDER_UNAVAILABLE,
                "Claude CLI '${claude.command}' could not be started; is it installed and on PATH?",
                e,
            )
        }

        // Drain stdout/stderr on separate threads: a process that fills a pipe buffer while we are
        // still writing stdin would otherwise deadlock.
        val stdoutReader = readerThread(process.inputStream, claude.maxOutputBytes)
        val stderrReader = readerThread(process.errorStream, claude.maxErrorBytes)

        try {
            process.outputStream.use { it.write(userPrompt.toByteArray(StandardCharsets.UTF_8)) }
        } catch (e: IOException) {
            process.destroyForcibly()
            throw AiCoachException(
                AiCoachException.Reason.PROVIDER_ERROR,
                "Claude CLI closed its input before the request could be sent",
                e,
            )
        }

        val finished = process.waitFor(claude.timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            throw AiCoachException(
                AiCoachException.Reason.TIMEOUT,
                "Claude CLI did not answer within ${claude.timeout.toSeconds()}s and was stopped",
            )
        }

        val stdout = stdoutReader.join(READER_JOIN_MILLIS)
        val stderr = stderrReader.join(READER_JOIN_MILLIS)
        val exit = process.exitValue()

        // Sizes and exit code only: the prompt and the response body are never logged.
        log.info(
            "Claude CLI call finished: exitCode={} stdoutBytes={} stderrBytes={}",
            exit, stdout.length, stderr.length,
        )

        if (exit != 0) {
            if (looksLikeAuthFailure(stderr) || looksLikeAuthFailure(stdout)) {
                throw AiCoachException(
                    AiCoachException.Reason.AUTH_REQUIRED,
                    "Claude CLI is not authenticated on this host; run 'claude' once and log in",
                )
            }
            if (looksLikeMissingCommand(stderr)) {
                throw AiCoachException(
                    AiCoachException.Reason.PROVIDER_UNAVAILABLE,
                    "Claude CLI '${claude.command}' was not found",
                )
            }
            throw AiCoachException(
                AiCoachException.Reason.PROVIDER_ERROR,
                "Claude CLI exited with code $exit",
            )
        }
        if (stdout.isBlank()) {
            throw AiCoachException(
                AiCoachException.Reason.INVALID_RESPONSE,
                "Claude CLI produced no output",
            )
        }
        return ClaudeCliResult(exit, stdout, stderr)
    }

    private fun looksLikeAuthFailure(text: String): Boolean {
        val lower = text.lowercase()
        return AUTH_MARKERS.any { lower.contains(it) }
    }

    private fun looksLikeMissingCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("not recognized") || lower.contains("command not found") ||
            lower.contains("no such file or directory")
    }

    /** Reads a stream to a bounded string on its own thread; anything past the cap is discarded. */
    private fun readerThread(stream: java.io.InputStream, maxBytes: Int): BoundedReader {
        val reader = BoundedReader(stream, maxBytes)
        reader.start()
        return reader
    }

    private class BoundedReader(private val stream: java.io.InputStream, private val maxBytes: Int) {
        private val buffer = StringBuilder()
        private val thread = Thread {
            try {
                stream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                    val chunk = CharArray(8192)
                    while (true) {
                        val read = r.read(chunk)
                        if (read < 0) break
                        val remaining = maxBytes - buffer.length
                        if (remaining <= 0) continue   // keep draining so the child never blocks
                        buffer.append(chunk, 0, minOf(read, remaining))
                    }
                }
            } catch (_: IOException) {
                // process died mid-read; whatever was captured is what the caller gets
            }
        }.apply { isDaemon = true }

        fun start() = thread.start()

        fun join(millis: Long): String {
            thread.join(millis)
            return buffer.toString()
        }
    }

    private companion object {
        const val READER_JOIN_MILLIS = 5_000L
        val AUTH_MARKERS = listOf(
            "not authenticated", "unauthenticated", "please log in", "please run /login",
            "invalid api key", "authentication_error", "oauth token has expired",
        )
    }
}
