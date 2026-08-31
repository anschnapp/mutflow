package io.github.anschnapp.mutflow.gradle

import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.HostManager

/**
 * Kotlin Multiplatform wiring (Phases 3 and 4 of DESIGN-MULTIPLATFORM.md).
 *
 * Both target kinds share the compilation model below and differ only in how
 * the mutation runs are driven: a native target gets one process per mutation
 * from an orchestrator task, while a jvm() target runs the ordinary in-process
 * JUnit path, exactly like a plain kotlin("jvm") project does.
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

        kotlin.targets.withType(KotlinJvmTarget::class.java).all { target ->
            configureJvmTarget(project, extension, target)
        }
    }

    /**
     * The jvm() target of a multiplatform project.
     *
     * Same mutatedMain/mutatedTest compilation model as Native, but the test
     * binary is a JUnit classpath rather than an executable, so the run loop
     * is the one that already exists: @MutFlowTest turns the class into a
     * JUnit @ClassTemplate and MutFlowExtension re-runs it once per mutation
     * in-process.
     *
     * The reason mutatedTest exists at all here (rather than reusing the stock
     * test compilation, as the plain kotlin("jvm") path does) is that the
     * commonTest sources cannot write @MutFlowTest: it is a JVM-only
     * annotation and commonTest must also compile for Native. So mutatedTest
     * is compiled a second time with the plugin in annotate mode, which
     * synthesizes the annotation into the bytecode. See TestClassAnnotator.
     */
    private fun configureJvmTarget(
        project: Project,
        extension: MutflowExtension,
        target: KotlinJvmTarget
    ) {
        val sourceSets = project.extensions
            .getByType(KotlinMultiplatformExtension::class.java)
            .sourceSets

        val mutatedMain = target.compilations.create(MutflowGradlePlugin.MUTATED_MAIN) { compilation ->
            compilation.defaultSourceSet.dependsOn(sourceSets.getByName("${target.name}Main"))
            compilation.defaultSourceSet.dependencies {
                implementation("${MutflowGradlePlugin.GROUP_ID}:mutflow-core:$MUTFLOW_VERSION")
            }
        }

        val mutatedTest = target.compilations.create(MUTATED_TEST) { compilation ->
            compilation.defaultSourceSet.dependsOn(sourceSets.getByName("${target.name}Test"))
            compilation.associateWith(mutatedMain)
            compilation.defaultSourceSet.dependencies {
                // The JUnit extension, plus the annotation the compiler plugin
                // synthesizes references. Only this compilation gets it; the
                // stock jvmTest compilation stays a plain kotlin.test run.
                implementation("${MutflowGradlePlugin.GROUP_ID}:mutflow-junit6:$MUTFLOW_VERSION")

                // Pinning the junit5 variant of kotlin-test explicitly is not
                // redundant. KGP picks that variant by inspecting the test
                // framework of the Test task wired to a compilation, and a
                // compilation this plugin creates has no such task at
                // resolution time - so a plain `kotlin("test")` in commonTest
                // resolves to the bare artifact here and `kotlin.test.Test`
                // does not resolve at all. This is the typealias that makes it
                // org.junit.jupiter.api.Test, which is what @ClassTemplate
                // discovery needs to see.
                implementation(
                    "org.jetbrains.kotlin:kotlin-test-junit5:${project.getKotlinPluginVersion()}"
                )
            }
        }

        // The stock jvmTest task keeps running uninstrumented code, exactly
        // as stock <target>Test does on Native. It does need one thing though:
        // its commonTest sources call MutFlow.underTest, and without a session
        // that call fails by design (a missing @MutFlowTest is a mistake worth
        // shouting about in a plain kotlin("jvm") project). Here there is no
        // annotation to miss - it is synthesized onto mutatedTest only - so
        // this tells the runtime to treat underTest as a pass-through, which
        // is the same Inactive mode Native falls into when no orchestrator set
        // any environment variable.
        target.testRuns.all { testRun ->
            testRun.executionTask.configure { task ->
                task.environment("MUTFLOW_INACTIVE", "true")
            }
        }

        val taskName = "mutflow${target.name.replaceFirstChar { it.uppercaseChar() }}Test"
        project.tasks.register(taskName, Test::class.java) { task ->
            task.group = "verification"
            task.description = "Runs mutflow mutation testing for the '${target.name}' target"
            task.testClassesDirs = mutatedTest.output.classesDirs
            task.classpath = mutatedTest.output.allOutputs +
                mutatedMain.output.allOutputs +
                mutatedTest.runtimeDependencyFiles
            task.useJUnitPlatform()

            // The mutflow { } DSL reaches the in-process run loop the same way
            // it reaches the native orchestrator: through the environment. The
            // synthesized @MutFlowTest always carries default arguments, so
            // annotation values are not a configuration surface in a
            // multiplatform project. An ambient environment variable still
            // wins, so a one-off CI override keeps working.
            // maxMutationRuns counts MUTATION runs, which is what the native
            // orchestrator plans. The JUnit extension counts total class
            // invocations, and the baseline is one of them - hence the +1, so
            // one DSL value means the same number of mutations on both paths.
            // Saturating, because the default is Int.MAX_VALUE.
            val maxRuns = extension.maxMutationRuns.get().let { runs ->
                if (runs == Int.MAX_VALUE) runs else runs + 1
            }
            task.environment("MUTFLOW_MAX_RUNS", maxRuns.toString())
            task.environment("MUTFLOW_TIMEOUT_MS", extension.timeoutMs.get().toString())
            val mode = System.getenv("MUTFLOW_VERIFICATION_MODE") ?: extension.verificationMode.get()
            task.environment("MUTFLOW_VERIFICATION_MODE", mode)

            // Gradle's Test task does not treat environment variables as
            // inputs, so without these the task stays UP-TO-DATE after a
            // mutflow { } change and silently reports the previous run's
            // result. The native orchestrator gets this for free: its
            // equivalents are @Input properties on a custom task type.
            task.inputs.property("mutflow.maxRuns", maxRuns)
            task.inputs.property("mutflow.timeoutMs", extension.timeoutMs.get())
            task.inputs.property("mutflow.verificationMode", mode)

            task.onlyIf("mutflow is disabled") { extension.enabled.get() }
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
                task.maxMutationRuns.set(extension.maxMutationRuns)
                task.timeoutMs.set(extension.timeoutMs)
                task.verificationMode.set(extension.verificationMode)
                task.onlyIf("mutflow is disabled") { extension.enabled.get() }
            }
            umbrella.configure { it.dependsOn(orchestrator) }
        }
    }
}
