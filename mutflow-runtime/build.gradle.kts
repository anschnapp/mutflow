plugins {
    // Switched from kotlin("jvm") to kotlin("multiplatform") for the Native effort.
    // Phase 1 ships the JVM target only; native targets are added in Phase 2.
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvm()

    sourceSets {
        // Session/selection/shuffle logic is pure Kotlin and lives in commonMain;
        // JVM-specific primitives (UUID, thread IDs, ConcurrentHashMap, system
        // clocks) sit behind expect/actual functions - see Platform.kt / Platform.jvm.kt.
        commonMain.dependencies {
            api(project(":mutflow-core"))
        }
        // Tests stay JVM-only for now: they use backtick-with-spaces test names,
        // which Kotlin/Native does not support, so they cannot move to commonTest
        // as-is when native targets arrive in Phase 2.
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
