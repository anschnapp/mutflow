package io.github.anschnapp.mutflow.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
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
        const val VERSION = "0.1.0-SNAPSHOT"
        const val MUTATED_MAIN = "mutatedMain"
        const val GROUP_ID = "io.github.anschnapp.mutflow"
    }

    override fun apply(target: Project) {
        target.plugins.withId("org.jetbrains.kotlin.jvm") {
            configureSourceSets(target)
            addDependencies(target)
        }
    }

    private fun configureSourceSets(project: Project) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val mainSourceSet = sourceSets.getByName("main")

        // Create mutatedMain source set that mirrors main sources
        val mutatedMain = sourceSets.create(MUTATED_MAIN) { sourceSet ->
            sourceSet.java.srcDirs(mainSourceSet.java.srcDirs)
            sourceSet.resources.srcDirs(mainSourceSet.resources.srcDirs)
        }

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

        // Tests use mutatedMain output instead of main
        project.dependencies.add("testImplementation", mutatedMain.output)

        // Configure test runtime classpath to prefer mutatedMain over main
        project.configurations.named("testRuntimeClasspath") { config ->
            config.exclude(mapOf(
                "group" to project.group.toString(),
                "module" to project.name
            ))
        }
    }

    private fun addDependencies(project: Project) {
        project.dependencies.add(
            "implementation",
            "$GROUP_ID:mutflow-core:$VERSION"
        )
        project.dependencies.add(
            "testImplementation",
            "$GROUP_ID:mutflow-junit6:$VERSION"
        )
    }

    // KotlinCompilerPluginSupportPlugin implementation

    override fun getCompilerPluginId(): String = "io.github.anschnapp.mutflow"

    override fun getPluginArtifact(): SubpluginArtifact = SubpluginArtifact(
        groupId = GROUP_ID,
        artifactId = "mutflow-compiler-plugin",
        version = VERSION
    )

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
        // ONLY apply to mutatedMain compilation - NOT main, NOT test
        // This ensures production JAR is clean while tests use mutated code
        return kotlinCompilation.name == MUTATED_MAIN
    }

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>
    ): Provider<List<SubpluginOption>> {
        return kotlinCompilation.target.project.provider { emptyList() }
    }
}
