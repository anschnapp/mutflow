package io.github.anschnapp.mutflow.gradle

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test

/**
 * Wires a mutation test task to a report task for ACCUMULATE mode, the same
 * way for a plain `kotlin("jvm")` project and the `jvm()` target of a
 * multiplatform one. The test task writes one results file per test class
 * into [resultsDirectory]; the report task merges them.
 */
internal object MutflowAccumulate {

    fun wire(
        project: Project,
        extension: MutflowExtension,
        testTask: TaskProvider<Test>,
        reportTaskName: String,
        resultsDirectory: Provider<Directory>
    ): TaskProvider<MutflowReport> {
        testTask.configure { task ->
            task.environment("MUTFLOW_RESULTS_DIR", resultsDirectory.get().asFile.absolutePath)
            task.outputs.dir(resultsDirectory)
            // Stale files from a previous run with a different filter or
            // mode would otherwise be merged as if they were current.
            task.doFirst { resultsDirectory.get().asFile.deleteRecursively() }
        }
        return project.tasks.register(reportTaskName, MutflowReport::class.java) { task ->
            task.group = "verification"
            task.description = "Merges the mutflow results of every test class into one report and judges the survivors"
            task.dependsOn(testTask)
            task.resultsDirectory.set(resultsDirectory)
            task.reportFile.set(project.layout.buildDirectory.file("reports/mutflow/mutation-report.md"))
            task.failOnSurvivors.set(extension.failOnSurvivors)
            task.onlyIf("mutflow is disabled") { extension.enabled.get() }
        }
    }
}
