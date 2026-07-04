// ============================================================================
// TEACHING NOTE: this is the interesting build file of the spike.
// It does the one thing the mutflow Gradle plugin cannot do yet: attach the
// mutflow COMPILER plugin to a Kotlin/NATIVE compilation.
// ============================================================================

import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    kotlin("multiplatform")
}

// ----------------------------------------------------------------------------
// TEACHING NOTE: Gradle plugin vs. compiler plugin (easy to conflate!)
// ----------------------------------------------------------------------------
// - A GRADLE plugin (like io.github.anschnapp.mutflow used in example/) runs
//   inside Gradle. mutflow's Gradle plugin normally does this wiring for JVM
//   test compilations via KotlinCompilerPluginSupportPlugin.
// - A COMPILER plugin (mutflow-compiler-plugin.jar) is loaded into the Kotlin
//   compiler process itself and transforms IR during compilation.
//
// For the spike we skip the Gradle plugin entirely and hand the compiler
// plugin jar to the compiler ourselves via the raw `-Xplugin=<path>` compiler
// flag. To get the jar path we declare a dedicated Gradle CONFIGURATION (a
// named bucket of dependencies) and let Gradle resolve it from mavenLocal.
// ----------------------------------------------------------------------------
val mutflowCompilerPlugin: Configuration by configurations.creating {
    // isTransitive=false: we want ONLY the plugin jar itself on the compiler
    // plugin classpath. Its declared dependency on mutflow-core is not needed
    // at plugin runtime (verified: the plugin references core types by FqName
    // strings only, never imports them) - and mutflow-core is a JVM jar that
    // has no business near a native compilation anyway.
    isTransitive = false
}

dependencies {
    // Requires a prior `./gradlew :mutflow-compiler-plugin:publishToMavenLocal`
    // in the mutflow repo root. THE JAR IS USED COMPLETELY UNMODIFIED - that
    // is the whole point of Phase 0: prove the existing plugin works on the
    // native backend without changes.
    mutflowCompilerPlugin("io.github.anschnapp.mutflow:mutflow-compiler-plugin:0.1.0-SNAPSHOT")
}

kotlin {
    // TEACHING NOTE: unlike a bare library target (see :stub-registry), a
    // target with test sources automatically gets a TEST BINARY. For linuxX64
    // this creates, among others:
    //   compileTestKotlinLinuxX64  -> test klib
    //   linkDebugTestLinuxX64      -> links build/bin/linuxX64/debugTest/test.kexe
    //   linuxX64Test               -> runs that .kexe
    // The .kexe is a normal standalone Linux executable - we will also run it
    // by hand with the MUTFLOW_ACTIVE_MUTATION env var set.
    linuxX64()

    sourceSets {
        // TEACHING NOTE: `commonMain.dependencies { }` is the KMP equivalent
        // of the top-level `dependencies { }` block in a JVM build. Deps
        // declared on commonMain flow down to every target.
        commonMain.dependencies {
            // Brings the stub MutationRegistry + @MutationTarget klib onto the
            // compile classpath, so (a) our source can use @MutationTarget and
            // (b) the compiler plugin's referenceClass(...) lookup finds
            // io.github.anschnapp.mutflow.MutationRegistry to inject calls to.
            implementation(project(":stub-registry"))
        }
        commonTest.dependencies {
            // kotlin("test") is multiplatform: on native targets it maps to
            // the built-in kotlin-test framework (which generates the test
            // main() of the .kexe), no JUnit involved.
            implementation(kotlin("test"))
        }
    }
}

// ----------------------------------------------------------------------------
// TEACHING NOTE: forcing the compiler plugin onto the native compilation
// ----------------------------------------------------------------------------
// `KotlinNativeCompile` is the task type that compiles Kotlin sources to a
// klib for a native target (both main and test compilations - the withType
// matcher catches both, which is what we want: Calculator lives in the main
// compilation).
//
// `-Xplugin=<jar>` tells the compiler to load compiler plugins from that jar
// (discovered via ServiceLoader - the jar declares its
// CompilerPluginRegistrar in META-INF/services). In K2 this same mechanism
// works across backends; IrGenerationExtension hooks run on the shared
// FIR2IR output before the backend takes over. THIS is the assumption the
// spike exists to verify.
//
// The `provider { }` wrapper defers resolving the configuration (a file
// download/lookup) until the task actually runs, instead of at Gradle
// configuration time - standard lazy-configuration hygiene.
//
// Note: no plugin OPTIONS (-P plugin:...) are passed. Target patterns are
// optional; the spike relies on the @MutationTarget annotation instead.
// ----------------------------------------------------------------------------
tasks.withType<KotlinNativeCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        provider { listOf("-Xplugin=${mutflowCompilerPlugin.singleFile.absolutePath}") }
    )
}
