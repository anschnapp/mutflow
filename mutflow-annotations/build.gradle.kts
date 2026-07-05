plugins {
    // Switched from kotlin("jvm") to kotlin("multiplatform") for the Native effort.
    // Phase 1 ships the JVM target only; native targets are added in Phase 2.
    // For JVM consumers nothing changes: the published root artifact carries Gradle
    // module metadata that transparently redirects them to the -jvm variant.
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvm()

    // Phase 2 native targets. linuxX64 is fully buildable and testable on the
    // Linux dev machine; mingwX64 (Windows) cross-compiles from Linux, which
    // gives compile-time proof without a Windows host (running its tests would
    // need one). Apple targets are deliberately absent: a macOS host is
    // required even to produce their klibs, so they follow once a Mac/CI
    // is available - adding them is just more one-liners here.
    linuxX64()
    mingwX64()

    // Both annotations are pure Kotlin and live entirely in commonMain,
    // so there are no jvmMain sources and no dependencies here.
}

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
