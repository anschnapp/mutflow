package io.github.anschnapp.mutflow.gradle

import io.github.anschnapp.mutflow.DiscoveryFileContent
import io.github.anschnapp.mutflow.MutflowFileFormatException
import io.github.anschnapp.mutflow.MutflowFiles
import io.github.anschnapp.mutflow.ResultFileContent
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Kotlin/Native mutation testing orchestrator (Phase 3 of
 * DESIGN-KOTLIN-NATIVE.md).
 *
 * One process = one run: this task launches the dedicated mutated test
 * binary once in discovery mode and then once per selected mutation,
 * communicating over the MUTFLOW_* environment variables and the
 * discovery/result files. The verdict is derived by exit-code inversion:
 * a nonzero exit (a test failed) means the mutation was KILLED, a zero exit
 * (all tests passed) means it SURVIVED.
 */
@DisableCachingByDefault(because = "mutation verdicts must be recomputed against the current tests on every run")
abstract class MutflowNativeTest : DefaultTask() {

    /** The linked mutated test executable (test.kexe) to orchestrate. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val testBinary: RegularFileProperty

    /** Where discovery/result files and per-run output logs are written. */
    @get:Internal
    abstract val workDirectory: DirectoryProperty

    /** Kotlin target name, only used for log output. */
    @get:Input
    abstract val targetName: Property<String>

    /**
     * Maximum number of mutation runs. Unlike the JVM annotation's maxRuns
     * this does NOT include the baseline: the discovery run always happens.
     * Default: unlimited, i.e. every discovered mutation is tested.
     */
    @get:Input
    abstract val maxMutationRuns: Property<Int>

    /** Per-underTest-block mutation deadline, passed as MUTFLOW_TIMEOUT_MS. */
    @get:Input
    abstract val timeoutMs: Property<Long>

    /** STRICT (survivors fail the build), LENIENT (report only) or DISABLED (baseline only). */
    @get:Input
    abstract val verificationMode: Property<String>

    init {
        // Mutation verdicts must be recomputed on every invocation - a green
        // report from a previous run says nothing about the current tests.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun orchestrate() {
        val binary = testBinary.get().asFile
        val workDir = workDirectory.get().asFile
        workDir.mkdirs()

        val mode = resolveVerificationMode()

        // ---- 1. Baseline / discovery run ----
        val discoveryFile = File(workDir, "discovery.json")
        discoveryFile.delete()
        logger.lifecycle("[mutflow] Baseline run (discovery) for target '${targetName.get()}'")
        val baseline = execBinary(
            binary = binary,
            env = mapOf("MUTFLOW_DISCOVERY_FILE" to discoveryFile.absolutePath),
            outputFile = File(workDir, "baseline-output.txt"),
            hardTimeoutMs = null
        )
        if (baseline.exitCode != 0) {
            logger.lifecycle(baseline.output)
            throw GradleException(
                "mutflow: baseline test run failed (exit code ${baseline.exitCode}). " +
                    "Fix the failing tests before mutation testing."
            )
        }

        val discovery = readDiscovery(discoveryFile)
        val totalMutations = discovery.points.sumOf { it.variantCount }
        logger.lifecycle(
            "[mutflow] Discovery: ${discovery.points.size} mutation points, $totalMutations mutations"
        )

        if (mode == "DISABLED") {
            logger.lifecycle("[mutflow] Verification mode DISABLED - skipping mutation runs")
            return
        }

        // ---- 2. Selection ----
        val plan = NativeOrchestration.planMutations(discovery, maxMutationRuns.get())

        // ---- 3. Mutation loop ----
        // A mutation run normally takes about as long as the baseline. The
        // in-process MUTFLOW_TIMEOUT_MS deadline catches infinite loops in
        // instrumented code; this hard process timeout is the safety net for
        // everything else (e.g. a mutation sending un-instrumented code into
        // a loop). Generous on purpose: it should only ever fire on hangs.
        val hardTimeoutMs = baseline.durationMs * 5 + timeoutMs.get() * 2 + 30_000
        val resultFile = File(workDir, "result.json")
        val results = mutableListOf<Pair<PlannedMutation, RunVerdict>>()

        for ((index, mutation) in plan.withIndex()) {
            resultFile.delete()
            val run = execBinary(
                binary = binary,
                env = mapOf(
                    "MUTFLOW_ACTIVE_MUTATION" to mutation.activeMutationValue,
                    "MUTFLOW_RESULT_FILE" to resultFile.absolutePath,
                    "MUTFLOW_TIMEOUT_MS" to timeoutMs.get().toString()
                ),
                outputFile = File(workDir, "run-output.txt"),
                hardTimeoutMs = hardTimeoutMs
            )
            val result = readResult(resultFile)
            val verdict = NativeOrchestration.classifyRun(
                exitCode = run.exitCode,
                result = result,
                firstFailedTest = NativeOrchestration.parseFirstFailedTest(run.output)
            )
            results.add(mutation to verdict)
            logRunVerdict(index + 1, plan.size, mutation, verdict)
        }

        // ---- 4. Report + verdict ----
        NativeOrchestration.renderSummary(results, totalMutations)
            .lines()
            .forEach { logger.lifecycle(it) }

        NativeOrchestration.buildFailureMessage(results, mode)?.let { message ->
            throw GradleException(message)
        }
    }

    private fun resolveVerificationMode(): String {
        // Same precedence as the JVM path: the environment variable overrides
        // the configured value (handy for one-off CI or local overrides).
        val fromEnv = System.getenv("MUTFLOW_VERIFICATION_MODE")
        val mode = (fromEnv ?: verificationMode.get()).uppercase()
        if (mode !in setOf("STRICT", "LENIENT", "DISABLED")) {
            throw GradleException(
                "mutflow: invalid verification mode '$mode' (expected STRICT, LENIENT or DISABLED)"
            )
        }
        return mode
    }

    private fun readDiscovery(discoveryFile: File): DiscoveryFileContent {
        if (!discoveryFile.isFile) {
            throw GradleException(
                "mutflow: the baseline run produced no discovery file (${discoveryFile}). " +
                    "The test binary does not seem to contain the mutflow runtime - " +
                    "check that the mutflow plugin versions match and the build is not misconfigured."
            )
        }
        return try {
            MutflowFiles.parseDiscoveryJson(discoveryFile.readText())
        } catch (e: MutflowFileFormatException) {
            throw GradleException("mutflow: cannot read discovery file: ${e.message}", e)
        }
    }

    private fun readResult(resultFile: File): ResultFileContent? {
        if (!resultFile.isFile) return null
        return try {
            MutflowFiles.parseResultJson(resultFile.readText())
        } catch (e: MutflowFileFormatException) {
            throw GradleException("mutflow: cannot read result file: ${e.message}", e)
        }
    }

    private fun logRunVerdict(current: Int, total: Int, mutation: PlannedMutation, verdict: RunVerdict) {
        val prefix = "[mutflow] ($current/$total)"
        when (verdict) {
            is RunVerdict.Killed ->
                logger.lifecycle("$prefix KILLED   ${mutation.displayName} (killed by: ${verdict.killedBy ?: "unknown"})")
            is RunVerdict.Survived ->
                logger.lifecycle(
                    "$prefix SURVIVED ${mutation.displayName}" +
                        if (verdict.touched) "" else " (mutated code never executed)"
                )
            is RunVerdict.TimedOut ->
                logger.lifecycle("$prefix TIMEOUT  ${mutation.displayName}")
        }
    }

    private data class ExecResult(
        /** Exit code, or null when the process was killed after [hardTimeoutMs]. */
        val exitCode: Int?,
        val output: String,
        val durationMs: Long
    )

    /**
     * Runs the test binary once. Output (stdout + stderr) is redirected to a
     * file instead of a pipe: no reader thread, no pipe-buffer deadlock when
     * combined with a bounded waitFor.
     */
    private fun execBinary(
        binary: File,
        env: Map<String, String>,
        outputFile: File,
        hardTimeoutMs: Long?
    ): ExecResult {
        val processBuilder = ProcessBuilder(binary.absolutePath)
            .directory(binary.parentFile)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
        processBuilder.environment().putAll(env)

        val start = System.nanoTime()
        val process = processBuilder.start()
        val exitCode: Int? = if (hardTimeoutMs == null) {
            process.waitFor()
        } else {
            if (process.waitFor(hardTimeoutMs, TimeUnit.MILLISECONDS)) {
                process.exitValue()
            } else {
                logger.lifecycle("[mutflow] Test binary exceeded hard timeout of ${hardTimeoutMs}ms - killing it")
                process.destroyForcibly()
                process.waitFor()
                null
            }
        }
        val durationMs = (System.nanoTime() - start) / 1_000_000
        return ExecResult(
            exitCode = exitCode,
            output = if (outputFile.isFile) outputFile.readText() else "",
            durationMs = durationMs
        )
    }
}
