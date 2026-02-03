package io.github.anschnapp.mutflow.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Gradle plugin for mutflow mutation testing.
 *
 * This plugin:
 * 1. Creates a 'mutatedMain' source set that compiles the same sources as 'main'
 *    but with the mutflow compiler plugin applied
 * 2. Configures tests to use mutatedMain classes instead of main
 * 3. Adds required mutflow dependencies
 *
 * Result: Production JAR is clean (no mutations), tests run against mutated code.
 */
class MutflowGradlePlugin : Plugin<Project>, KotlinCompilerPluginSupportPlugin {

    companion object {
        const val MUTATED_MAIN = "mutatedMain"
        const val GROUP_ID = "io.github.anschnapp.mutflow"
        private const val DEBUG = false

        private fun debug(msg: String) {
            if (DEBUG) {
                println("[MUTFLOW-GRADLE] $msg")
            }
        }
    }

    override fun apply(target: Project) {
        debug("apply() called for project: ${target.name}")
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            debug("  kotlin.jvm plugin detected, configuring...")
            configureSourceSets(target)
            addDependencies(target)
            debug("  configuration complete")
        }
    }

    /**
     * Gets the Kotlin source directory set from a Java source set.
     * The Kotlin plugin adds a "kotlin" extension to each SourceSet.
     */
    private fun SourceSet.kotlinSourceDirectorySet(): SourceDirectorySet? {
        return extensions.findByName("kotlin") as? SourceDirectorySet
    }

    private fun configureSourceSets(project: Project) {
        debug("configureSourceSets()")
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val mainSourceSet = sourceSets.getByName("main")
        val mainKotlin = mainSourceSet.kotlinSourceDirectorySet()

        debug("  main java.srcDirs: ${mainSourceSet.java.srcDirs}")
        debug("  main kotlin.srcDirs: ${mainKotlin?.srcDirs ?: "N/A"}")

        // Create mutatedMain source set that mirrors main sources
        val mutatedMain = sourceSets.create(MUTATED_MAIN) { sourceSet ->
            sourceSet.java.srcDirs(mainSourceSet.java.srcDirs)
            sourceSet.resources.srcDirs(mainSourceSet.resources.srcDirs)
            // Copy Kotlin sources - must be done after source set creation
            // because the kotlin extension is added by the Kotlin plugin
        }

        // Configure Kotlin sources for mutatedMain (must be done after creation)
        val mutatedMainKotlin = mutatedMain.kotlinSourceDirectorySet()
        if (mainKotlin != null && mutatedMainKotlin != null) {
            mutatedMainKotlin.srcDirs(mainKotlin.srcDirs)
            debug("  configured kotlin.srcDirs for mutatedMain")
        } else {
            debug("  WARNING: Could not configure Kotlin sources (mainKotlin=$mainKotlin, mutatedMainKotlin=$mutatedMainKotlin)")
        }

        debug("  created mutatedMain source set")
        debug("    java.srcDirs: ${mutatedMain.java.srcDirs}")
        debug("    kotlin.srcDirs: ${mutatedMainKotlin?.srcDirs ?: "N/A"}")

        // mutatedMain needs same dependencies as main
        project.configurations.named("${MUTATED_MAIN}Implementation") {
            it.extendsFrom(project.configurations.getByName("implementation"))
        }
        project.configurations.named("${MUTATED_MAIN}CompileOnly") {
            it.extendsFrom(project.configurations.getByName("compileOnly"))
        }
        project.configurations.named("${MUTATED_MAIN}RuntimeOnly") {
            it.extendsFrom(project.configurations.getByName("runtimeOnly"))
        }
        debug("  configured dependency inheritance")

        // Tests use mutatedMain output instead of main
        project.dependencies.add("testImplementation", mutatedMain.output)

        // Configure test tasks to use mutatedMain classes FIRST on classpath
        // This ensures mutated classes are loaded instead of original main classes
        project.tasks.withType(Test::class.java).configureEach { testTask ->
            testTask.dependsOn("compileMutatedMainKotlin")
            // Prepend mutatedMain class directories to the classpath
            // Only classes, not resources - to avoid breaking Spring/Testcontainers resource loading
            testTask.classpath = project.files(mutatedMain.output.classesDirs) + testTask.classpath
            debug("  configured test task '${testTask.name}' to use mutatedMain classes first")
        }
    }

    private fun addDependencies(project: Project) {
        project.dependencies.add(
            "implementation",
            "$GROUP_ID:mutflow-core:$MUTFLOW_VERSION"
        )
        project.dependencies.add(
            "testImplementation",
            "$GROUP_ID:mutflow-junit6:$MUTFLOW_VERSION"
        )
    }

    // KotlinCompilerPluginSupportPlugin implementation

    override fun getCompilerPluginId(): String {
        debug("getCompilerPluginId() -> io.github.anschnapp.mutflow")
        return "io.github.anschnapp.mutflow"
    }

    override fun getPluginArtifact(): SubpluginArtifact {
        debug("getPluginArtifact() -> $GROUP_ID:mutflow-compiler-plugin:$MUTFLOW_VERSION")
        return SubpluginArtifact(
            groupId = GROUP_ID,
            artifactId = "mutflow-compiler-plugin",
            version = MUTFLOW_VERSION
        )
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        val compilationName = kotlinCompilation.name
        val isApplicable = compilationName == MUTATED_MAIN
        debug("isApplicable(compilation='$compilationName') -> $isApplicable")
        return isApplicable
    }

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        debug("applyToCompilation(compilation='${kotlinCompilation.name}')")
        return kotlinCompilation.target.project.provider { emptyList() }
    }
}
