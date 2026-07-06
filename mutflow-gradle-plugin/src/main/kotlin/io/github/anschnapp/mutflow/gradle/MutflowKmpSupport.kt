package io.github.anschnapp.mutflow.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.HostManager

/**
 * Kotlin Multiplatform wiring for the Native mutation testing path
 * (Phase 3 of DESIGN-KOTLIN-NATIVE.md).
 *
 * Kept in its own file so the classes from kotlin-gradle-plugin internals
 * (KotlinNativeTarget, HostManager, ...) are only loaded when the
 * multiplatform plugin is actually present.
 *
 * The "clean from the start" model: the regular main compilation of every
 * native target stays untouched, so production klibs and release binaries
 * never contain instrumentation. Instead each native target gets
 *
 *   mutatedMain  - a second compilation of the exact same sources, with the
 *                  mutflow compiler plugin applied (the KotlinNativeCompile
 *                  task is matched by compilation name in isApplicable)
 *   mutatedTest  - a second compilation of the test sources, associated with
 *                  mutatedMain instead of main, so test code resolves
 *                  against the instrumented klib (and only that one)
 *   a "mutated" test binary linked from mutatedTest
 *
 * This is the native equivalent of the JVM path's mutatedMain source set
 * trick. The plain `<target>Test` task keeps running the uninstrumented
 * binary; the orchestrator task drives the mutated one.
 */
internal object MutflowKmpSupport {

    const val MUTATED_TEST = "mutatedTest"
    const val MUTATED_BINARY_PREFIX = "mutated"

    fun configure(project: Project, extension: MutflowExtension) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

        // The user-facing dependencies mirror the JVM path: production code
        // needs the annotations, test code needs the underTest API. Both are
        // multiplatform artifacts, so declaring them on the common source
        // sets covers every target (including a jvm() target, where they are
        // harmless without the JVM wiring).
        kotlin.sourceSets.getByName("commonMain").dependencies {
            implementation("${MutflowGradlePlugin.GROUP_ID}:mutflow-annotations:$MUTFLOW_VERSION")
        }
        kotlin.sourceSets.getByName("commonTest").dependencies {
            implementation("${MutflowGradlePlugin.GROUP_ID}:mutflow-runtime:$MUTFLOW_VERSION")
        }

        // The gradle property gate is read eagerly on purpose: the mutated
        // compilations have to be created while the kotlin { } block is being
        // evaluated (KGP finalizes its model in afterEvaluate, which runs
        // before any afterEvaluate this plugin could register). Setting
        // `mutflow { enabled = false }` in the build script still disables
        // instrumentation (isApplicable checks it lazily) and the
        // orchestrator tasks (onlyIf), just not the compilation creation.
        val enabledByProperty = project.providers.gradleProperty("mutflow.enabled")
            .map { it.toBoolean() }
            .getOrElse(true)
        if (!enabledByProperty) {
            return
        }

        val umbrella = project.tasks.register("mutflowNativeTest") { task ->
            task.group = "verification"
            task.description = "Runs mutflow mutation testing for all Kotlin/Native targets runnable on this host"
        }

        kotlin.targets.withType(KotlinNativeTarget::class.java).all { target ->
            configureNativeTarget(project, extension, target, umbrella)
        }
    }

    private fun configureNativeTarget(
        project: Project,
        extension: MutflowExtension,
        target: KotlinNativeTarget,
        umbrella: TaskProvider<*>
    ) {
        val sourceSets = project.extensions
            .getByType(KotlinMultiplatformExtension::class.java)
            .sourceSets

        // Second compilation of the production sources. dependsOn pulls in
        // the target's main source set INCLUDING its whole dependsOn closure
        // (commonMain, nativeMain, ...), so this compiles exactly what main
        // compiles - just with the compiler plugin applied.
        //
        // KGP warns about this edge (KotlinSourceSetDependsOnDefaultCompilationSourceSet,
        // suppressible via kotlin.suppressGradlePluginWarnings in the
        // consumer's gradle.properties). The edge is deliberate anyway: it is
        // the only wiring that transitively follows WHATEVER source set
        // hierarchy the project uses. The alternatives all break down -
        // copying the dependsOn parents is impossible at configuration time
        // (KGP applies the default hierarchy template in a later lifecycle
        // stage; the sets are observably empty even in afterEvaluate), and
        // flattening all srcDirs into one source set would break every
        // project that uses expect/actual.
        val mutatedMain = target.compilations.create(MutflowGradlePlugin.MUTATED_MAIN) { compilation ->
            compilation.defaultSourceSet.dependsOn(sourceSets.getByName("${target.name}Main"))
            // The instrumented code calls MutationRegistry.check(), so the
            // mutated compilation (and only it) needs mutflow-core. The
            // clean main compilation never sees it.
            compilation.defaultSourceSet.dependencies {
                implementation("${MutflowGradlePlugin.GROUP_ID}:mutflow-core:$MUTFLOW_VERSION")
            }
        }

        // Second compilation of the test sources, resolving against the
        // instrumented klib. associateWith gives it the same internal
        // visibility into mutatedMain that the default test compilation has
        // into main. Deliberately NOT associated with main: both klibs on
        // the classpath would mean every symbol exists twice.
        val mutatedTest = target.compilations.create(MUTATED_TEST) { compilation ->
            compilation.defaultSourceSet.dependsOn(sourceSets.getByName("${target.name}Test"))
            compilation.associateWith(mutatedMain)
        }

        target.binaries.test(MUTATED_BINARY_PREFIX, listOf(NativeBuildType.DEBUG)) { binary ->
            binary.compilation = mutatedTest

            // The orchestrator can only execute binaries of the build host.
            // For cross-compiled targets (e.g. mingwX64 on Linux) the mutated
            // binary still builds - that is the compile-proof - but no
            // orchestrator task is registered, matching how KGP itself only
            // runs <target>Test on a matching host.
            if (target.konanTarget != HostManager.host) {
                return@test
            }

            val taskName = "mutflow${target.name.replaceFirstChar { it.uppercaseChar() }}Test"
            val orchestrator = project.tasks.register(taskName, MutflowNativeTest::class.java) { task ->
                task.group = "verification"
                task.description = "Runs mutflow mutation testing for Kotlin/Native target '${target.name}'"
                task.dependsOn(binary.linkTaskProvider)
                task.testBinary.set(project.layout.file(project.provider { binary.outputFile }))
                task.workDirectory.set(project.layout.buildDirectory.dir("mutflow/${target.name}"))
                task.targetName.set(target.name)
                task.maxMutationRuns.set(extension.nativeMaxMutationRuns)
                task.timeoutMs.set(extension.nativeTimeoutMs)
                task.verificationMode.set(extension.nativeVerificationMode)
                task.onlyIf("mutflow is disabled") { extension.enabled.get() }
            }
            umbrella.configure { it.dependsOn(orchestrator) }
        }
    }
}
