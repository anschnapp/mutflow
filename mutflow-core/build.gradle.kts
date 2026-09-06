plugins {
    // Multiplatform rather than kotlin("jvm"): this module is consumed by both
    // the JVM and the Kotlin/Native mutation paths. For JVM consumers nothing
    // changes, the published root artifact carries Gradle module metadata that
    // transparently redirects them to the -jvm variant.
    kotlin("multiplatform")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvm()

    // ---- Declared targets = published artifacts = supported targets ----
    //
    // CANONICAL COPY of this explanation; the other two KMP modules point here.
    //
    // This list is not a record of what we happened to test; it IS the support
    // boundary, and that is a property of Kotlin Multiplatform rather than a
    // policy of ours. KMP resolves a dependency per target variant, and a
    // consumer may only depend on a library whose target set is a SUPERSET of
    // its own. A project declaring macosArm64() against a mutflow that does
    // not publish macosArm64 therefore gets a hard "no matching variant"
    // resolution failure - not a degraded mutflow, not an untested one.
    // Adding a target here is what makes it usable at all.
    //
    // Why exactly these two:
    //   linuxX64  - built AND tested, on the dev machine and in CI.
    //   mingwX64  - cross-compiles from Linux, which gives compile-time proof
    //               that the posix code below is Windows-portable. Running its
    //               tests would need a Windows host, so it is compile-proof
    //               only (the same status KGP itself gives mingwX64Test).
    //
    // Apple targets are absent by choice, and the reason is narrower than the
    // usual "you need a Mac". Verified on Kotlin 2.4 from the Linux dev
    // machine: adding macosArm64 and iosSimulatorArm64 and running
    // publishToMavenLocal produces real, complete .klib artifacts. Apple klib
    // CROSS-COMPILATION needs no macOS host and no opt-in flag.
    //
    // What a Mac is still needed for is linking and RUNNING an Apple test
    // binary - and for a mutation testing tool that is the whole product. The
    // orchestrator's entire job is executing a test binary once per mutation,
    // so a target nobody can run is a target nobody can verify. Declaring
    // Apple targets would ship them at exactly the confidence level mingwX64
    // has today: compile-proof, never executed. That is a support promise to
    // make deliberately, not a side effect of adding a line here.
    //
    // A developer who wants such a target NOW - and who, on a Mac, can also
    // run it - does not need to edit this file; see the apply(from = ...) at
    // the bottom.
    //
    // The Kotlin Gradle plugin's default hierarchy template automatically
    // creates shared nativeMain/nativeTest source sets above these targets,
    // which is where the posix-based actuals in src/nativeMain live. The
    // compiler "commonizes" platform.posix across the declared targets, so
    // nativeMain only sees POSIX API that exists on BOTH linux and mingw -
    // a compile error there means a Windows portability problem was caught
    // early.
    //
    // The same mechanism is why adding an Apple target is genuinely just one
    // more line here: appleMain sits under nativeMain in that template, so the
    // actuals in src/nativeMain become the actuals for macosArm64 and friends
    // with no new source set and no new code.
    linuxX64()
    mingwX64()

    sourceSets {
        // Registry logic lives in commonMain; the few JVM-specific primitives
        // (synchronized, ConcurrentHashMap, System.nanoTime) sit behind
        // expect/actual functions - see Platform.kt / Platform.jvm.kt.
        commonMain.dependencies {
            api(project(":mutflow-annotations"))
        }
        // Tests that are pure common code (JSON serialization) live in
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

// Lets a developer add unpublished native targets locally without editing this
// file, e.g. on a Mac:
//   ./gradlew publishToMavenLocal -Pmutflow.extraNativeTargets=macosArm64
// Applied after the kotlin { } block above so the baseline targets exist first.
// Every KMP module applies the same script and reads the same property, which
// is what keeps their target sets identical - the invariant the subset rule
// above depends on.
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
