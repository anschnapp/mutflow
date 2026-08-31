plugins {
    // Switched from kotlin("jvm") to kotlin("multiplatform") for the Native effort.
    // Phase 1 ships the JVM target only; native targets are added in Phase 2.
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvm()

    // Phase 2 native targets - must match mutflow-core's target set exactly:
    // a KMP library can only depend on another KMP library if the consumer's
    // targets are a subset of the producer's. See mutflow-core's build file
    // for why this exact pair (linux verified, mingw compile-proven).
    linuxX64()
    mingwX64()

    sourceSets {
        // Session/selection/shuffle logic is pure Kotlin and lives in commonMain;
        // JVM-specific primitives (UUID, thread IDs, ConcurrentHashMap, system
        // clocks) sit behind expect/actual functions - see MutFlowPlatform.kt /
        // MutFlowPlatform.jvm.kt.
        commonMain.dependencies {
            api(project(":mutflow-core"))
        }
        // Phase 2: ProcessRun tests are pure common code (fake writers, no file
        // IO) and live in commonTest with plain function names, so they run on
        // every target. The pre-existing MutFlow tests stay in jvmTest: they
        // use backtick-with-spaces test names, which Kotlin/Native does not
        // support.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
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
        name.set("mutflow-runtime")
        description.set("Runtime support for Mutflow - Lightweight mutation testing for Kotlin")
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
