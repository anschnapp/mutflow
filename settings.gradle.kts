pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        kotlin("jvm") version kotlinVersion
    }
}

rootProject.name = "mutflow"

include("mutflow-core")
include("mutflow-runtime")
include("mutflow-compiler-plugin")
include("mutflow-junit6")
include("mutflow-test-sample")
