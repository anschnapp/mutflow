pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
        // Same artifact (kotlin-gradle-plugin) as kotlin("jvm"), just a different
        // plugin id - used by the KMP modules (annotations, core, runtime).
        kotlin("multiplatform") version kotlinVersion
    }
}

rootProject.name = "mutflow"

include("mutflow-annotations")
include("mutflow-core")
include("mutflow-runtime")
include("mutflow-compiler-plugin")
include("mutflow-junit6")
include("mutflow-gradle-plugin")
include("mutflow-test-sample")
