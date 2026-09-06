plugins {
    // Multiplatform rather than kotlin("jvm"): the annotations are resolved by
    // the compiler plugin on every backend. For JVM consumers nothing changes:
    // the published root artifact carries Gradle module metadata that
    // transparently redirects them to the -jvm variant.
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvm()

    // ---- Declared targets = published artifacts = supported targets ----
    //
    // This list is not a record of what we happened to test; it IS the support
    // boundary. A Kotlin Multiplatform consumer can only depend on a library
    // whose target set is a superset of its own, so a project declaring
    // macosArm64() against a mutflow that does not publish macosArm64 gets a
    // hard "no matching variant" resolution failure, not a degraded mutflow.
    //
    // All three KMP modules (annotations, core, runtime) must therefore
    // declare the SAME set. The full reasoning - why exactly these two, and
    // why Apple targets are absent - lives in mutflow-core/build.gradle.kts,
    // which is the canonical copy of this comment.
    linuxX64()
    mingwX64()

    // Both annotations are pure Kotlin and live entirely in commonMain,
    // so there are no jvmMain sources and no dependencies here.
}

// Lets a developer add unpublished native targets locally without editing this
// file, e.g. on a Mac:
//   ./gradlew publishToMavenLocal -Pmutflow.extraNativeTargets=macosArm64
// Applied after the kotlin { } block above so the baseline targets exist first.
// Every KMP module applies the same script and reads the same property, which
// is what keeps their target sets identical.
apply(from = rootProject.file("gradle/extra-native-targets.gradle.kts"))

// The multiplatform plugin creates per-target test tasks (jvmTest) plus an
// `allTests` lifecycle task, but no plain `test` task like kotlin("jvm") did.
// This alias keeps `./gradlew test` working across the whole build.
tasks.register("test") {
    dependsOn("jvmTest")
}

mavenPublishing {
    publishToMavenCentral()

    // Only sign when credentials are available (CI environment)
    if (project.hasProperty("signingInMemoryKey") || System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

    pom {
        name.set("mutflow-annotations")
        description.set("Annotations for Mutflow - Lightweight mutation testing for Kotlin")
        url.set("https://github.com/anschnapp/mutflow")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("anschnapp")
                name.set("Andreas Schnapp")
                url.set("https://github.com/anschnapp")
            }
        }

        scm {
            url.set("https://github.com/anschnapp/mutflow")
            connection.set("scm:git:git://github.com/anschnapp/mutflow.git")
            developerConnection.set("scm:git:ssh://git@github.com/anschnapp/mutflow.git")
        }
    }
}
