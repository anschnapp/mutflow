// ============================================================================
// TEACHING NOTE: What this file is
// ----------------------------------------------------------------------------
// settings.gradle.kts is the entry point of a Gradle *build*. Its presence is
// what makes `spike/` a completely standalone build: when you run Gradle from
// inside this directory, Gradle finds THIS file and never looks at the mutflow
// root project. The mutflow root settings.gradle.kts likewise does not
// `include()` this directory, so the two builds are invisible to each other.
// (Same pattern as the existing `example/` project.)
//
// We deliberately do NOT depend on mutflow's modules as Gradle projects.
// Instead we consume the compiler plugin as a published artifact from
// mavenLocal - exactly like a real user would - which is why you must run
// `./gradlew :mutflow-compiler-plugin:publishToMavenLocal` in the repo root
// before building this spike.
// ============================================================================

rootProject.name = "mutflow-native-spike"

// ----------------------------------------------------------------------------
// TEACHING NOTE: pluginManagement
// ----------------------------------------------------------------------------
// This block controls where Gradle finds *Gradle plugins* (the things in
// `plugins { }` blocks of build scripts) and can pin their versions centrally.
//
// - `mavenLocal()` is listed first so a locally published mutflow Gradle
//   plugin would win over remote ones. For this spike we don't actually use
//   the mutflow *Gradle* plugin at all (we wire the *compiler* plugin by hand,
//   see app/build.gradle.kts), but keeping mavenLocal here is harmless and
//   matches the example/ project.
// - Declaring the kotlin("multiplatform") version here means the subprojects
//   can apply it without repeating the version string.
// ----------------------------------------------------------------------------
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        // Must match the Kotlin version mutflow is built against (see
        // ../gradle.properties -> kotlinVersion). The compiler plugin jar is
        // loaded INTO the Kotlin compiler process, so version skew between
        // the plugin's compile-time Kotlin APIs and the actual compiler can
        // break in subtle ways. Keep them identical for the spike.
        kotlin("multiplatform") version "2.4.0"
    }
}

// ----------------------------------------------------------------------------
// TEACHING NOTE: dependencyResolutionManagement
// ----------------------------------------------------------------------------
// Central place for *library* repositories (as opposed to plugin repositories
// above). All subprojects inherit these, so individual build.gradle.kts files
// don't need their own `repositories { }` blocks.
// mavenLocal() first: that's where the locally published
// mutflow-compiler-plugin-0.1.0-SNAPSHOT.jar lives (~/.m2/repository/...).
// ----------------------------------------------------------------------------
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}

// ----------------------------------------------------------------------------
// TEACHING NOTE: the two subprojects
// ----------------------------------------------------------------------------
// :stub-registry - a tiny Kotlin/Native library (klib) that provides fake
//                  stand-ins for io.github.anschnapp.mutflow.MutationRegistry
//                  and @MutationTarget. The compiler plugin resolves these
//                  purely BY FULLY QUALIFIED NAME (see MutflowIrTransformer:
//                  FqName("io.github.anschnapp.mutflow.MutationRegistry")),
//                  so any native-compiled class with the right FQN and
//                  signatures satisfies it. This is the "stubbed registry"
//                  from Phase 0 of DESIGN-KOTLIN-NATIVE.md.
//
// :app           - the code under test: one @MutationTarget class, one
//                  kotlin-test suite, and the build logic that forces the
//                  existing (unmodified!) mutflow compiler plugin onto the
//                  Kotlin/Native compilation.
//
// Why two projects instead of one? On the JVM path, MutationRegistry always
// comes from a separate artifact (mutflow-core) on the compile classpath.
// Mirroring that here (stub as a separate klib dependency) keeps the spike
// faithful and avoids betting on whether `pluginContext.referenceClass` can
// resolve symbols from the module currently being compiled.
// ----------------------------------------------------------------------------
include(":stub-registry")
include(":app")
