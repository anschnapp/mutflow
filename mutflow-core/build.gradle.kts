plugins {
    // Switched from kotlin("jvm") to kotlin("multiplatform") for the Native effort.
    // Phase 1 ships the JVM target only; native targets are added in Phase 2.
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvm()

    // Phase 2 native targets. linuxX64 is fully buildable and testable on the
    // Linux dev machine; mingwX64 (Windows) cross-compiles from Linux, which
    // gives compile-time proof without a Windows host (running its tests would
    // need one). Apple targets are deliberately absent: a macOS host is
    // required even to produce their klibs, so they follow once a Mac/CI is
    // available - adding them is just more one-liners here.
    //
    // The Kotlin Gradle plugin's default hierarchy template automatically
    // creates shared nativeMain/nativeTest source sets above these targets,
    // which is where the posix-based actuals in src/nativeMain live. The
    // compiler "commonizes" platform.posix across the declared targets, so
    // nativeMain only sees POSIX API that exists on BOTH linux and mingw -
    // a compile error there means a Windows portability problem was caught
    // early.
    linuxX64()
    mingwX64()

    sourceSets {
        // Registry logic lives in commonMain; the few JVM-specific primitives
        // (synchronized, ConcurrentHashMap, System.nanoTime) sit behind
        // expect/actual functions - see Platform.kt / Platform.jvm.kt.
        commonMain.dependencies {
            api(project(":mutflow-annotations"))
        }
        // Phase 2: tests that are pure common code (JSON serialization) live in
        // commonTest with plain function names, so they run on every target
        // (jvmTest, linuxX64Test, ...). The pre-existing registry tests stay in
        // jvmTest: they use backtick-with-spaces test names, which
        // Kotlin/Native does not support.
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
        name.set("mutflow-core")
        description.set("Core registry for Mutflow - Lightweight mutation testing for Kotlin")
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
