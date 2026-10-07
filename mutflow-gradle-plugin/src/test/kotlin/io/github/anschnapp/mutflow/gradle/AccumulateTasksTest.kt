package io.github.anschnapp.mutflow.gradle

import org.gradle.api.Project
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ACCUMULATE pipeline's tasks exist only when the mode resolves to ACCUMULATE, and carry the
 * mode in their names. The multiplatform jvm() target keeps its mutation test task in every mode.
 */
class AccumulateTasksTest {

    private fun project(kotlinPlugin: String, mode: String?, declareTargets: Project.() -> Unit = {}): Project {
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(kotlinPlugin)
        project.pluginManager.apply(MutflowGradlePlugin::class.java)
        project.declareTargets()
        mode?.let { project.extensions.getByType(MutflowExtension::class.java).verificationMode.set(it) }
        (project as ProjectInternal).evaluate()
        return project
    }

    private fun jvmProject(mode: String?) = project("org.jetbrains.kotlin.jvm", mode)

    private fun kmpProject(mode: String?) = project("org.jetbrains.kotlin.multiplatform", mode) {
        extensions.getByType(KotlinMultiplatformExtension::class.java).jvm()
    }

    private fun Project.mutflowTaskNames(): Set<String> = tasks.names.filter { it.startsWith("mutflow") }.toSet()

    @Test
    fun `a plain JVM project in ACCUMULATE mode gets the accumulate test and report tasks`() {
        assertEquals(setOf("mutflowAccumulateTest", "mutflowAccumulateReport"), jvmProject("ACCUMULATE").mutflowTaskNames())
    }

    @Test
    fun `a plain JVM project in any other mode gets no mutflow tasks`() {
        for (mode in listOf(null, "STRICT", "LENIENT", "DISABLED")) {
            assertEquals(emptySet(), jvmProject(mode).mutflowTaskNames(), "mode $mode")
        }
    }

    @Test
    fun `the jvm target gets its accumulate report only in ACCUMULATE mode`() {
        val accumulate = kmpProject("ACCUMULATE").mutflowTaskNames()
        assertTrue("mutflowJvmTest" in accumulate && "mutflowJvmAccumulateReport" in accumulate, "got $accumulate")
        assertTrue(accumulate.none { it == "mutflowJvmReport" }, "got $accumulate")

        val strict = kmpProject(null).mutflowTaskNames()
        assertTrue("mutflowJvmTest" in strict, "got $strict")
        assertTrue(strict.none { it.endsWith("Report") }, "got $strict")
    }
}
