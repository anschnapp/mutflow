// Standalone build, same pattern as example/ and spike/: running Gradle from
// this directory uses THIS settings file, the mutflow root build never sees
// it. All mutflow artifacts (Gradle plugin included) come from mavenLocal,
// exactly like for a real user - publish them first from the repo root:
//   ./gradlew publishToMavenLocal
rootProject.name = "mutflow-example-native"

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        // Must match the Kotlin version mutflow is built against
        // (../gradle.properties -> kotlinVersion): the compiler plugin runs
        // inside this Kotlin compiler process.
        kotlin("multiplatform") version "2.4.0"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
