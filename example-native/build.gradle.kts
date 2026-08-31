plugins {
    kotlin("multiplatform")
    // The mutflow Gradle plugin does the entire native wiring on its own:
    //  - creates the mutatedMain/mutatedTest compilations per native target
    //    (instrumented copies of the regular compilations; production klibs
    //    and binaries stay clean)
    //  - registers the mutflow<Target>Test orchestration tasks plus the
    //    mutflowNativeTest umbrella task
    //  - adds the mutflow dependencies (annotations to commonMain, runtime
    //    to commonTest, core to the mutated compilations)
    id("io.github.anschnapp.mutflow") version "0.1.0-SNAPSHOT"
}

group = "com.example"
version = "1.0-SNAPSHOT"

kotlin {
    // The jvm() target runs the SAME commonTest sources through the ordinary
    // in-process JUnit path (`mutflowJvmTest`). Nothing in commonTest names a
    // JVM type: the compiler plugin synthesizes @MutFlowTest onto the test
    // classes of the jvm() target's mutatedTest compilation.
    jvm()

    linuxX64()
    // Cross-compiles from Linux (compile proof); the orchestration task for
    // it only exists on a Windows host, mirroring how Gradle's own
    // mingwX64Test behaves.
    mingwX64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

mutflow {
    // Short deadline so a mutation that causes an infinite loop (see
    // Calculator.sumUpTo) is broken quickly; the default is 60s. Applies to
    // both paths: the native orchestrator passes it as MUTFLOW_TIMEOUT_MS to
    // each mutation process, the mutflowJvmTest task into the JUnit run.
    timeoutMs = 3_000L
}


