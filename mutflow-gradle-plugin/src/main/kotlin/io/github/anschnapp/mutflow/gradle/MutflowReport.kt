package io.github.anschnapp.mutflow.gradle

import io.github.anschnapp.mutflow.MutflowFileFormatException
import io.github.anschnapp.mutflow.MutflowFiles
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Merges the per-test-class results files of ACCUMULATE mode into one report
 * and gives the verdict a single test class cannot: a mutant survives only if
 * no test class that reached it killed it. See [ResultsMerge].
 */
@DisableCachingByDefault(because = "a verdict must be recomputed from the current test results on every run")
abstract class MutflowReport : DefaultTask() {

    /** Where the test task wrote one `<TestClass>.json` per mutation-tested class. */
    @get:Internal
    abstract val resultsDirectory: DirectoryProperty

    /** The markdown report. */
    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /** Whether surviving mutations fail the build. */
    @get:Input
    abstract val failOnSurvivors: Property<Boolean>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun report() {
        val dir = resultsDirectory.get().asFile
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) {
            throw GradleException(
                "mutflow: no results in $dir. Results are written by test classes running in ACCUMULATE " +
                    "verification mode (mutflow { verificationMode = \"ACCUMULATE\" }, or MUTFLOW_VERIFICATION_MODE=ACCUMULATE)."
            )
        }
        val sessions = files.map { file ->
            try {
                MutflowFiles.parseSessionResultsJson(file.readText())
            } catch (e: MutflowFileFormatException) {
                throw GradleException("mutflow: cannot read results file ${file.name}: ${e.message}", e)
            }
        }

        val results = ResultsMerge.merge(sessions)
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(ResultsMerge.renderReport(results))

        ResultsMerge.renderConsoleSummary(results, report.absolutePath).lines()
            .filter { it.isNotEmpty() }
            .forEach { logger.lifecycle(it) }

        ResultsMerge.buildFailureMessage(results, failOnSurvivors.get())?.let { throw GradleException(it) }
    }
}
