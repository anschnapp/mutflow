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


