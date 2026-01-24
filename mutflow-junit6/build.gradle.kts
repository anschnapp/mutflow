plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":mutflow-runtime"))

    implementation("org.junit.jupiter:junit-jupiter-api:${property("junitVersion")}")
    implementation("org.junit.jupiter:junit-jupiter-engine:${property("junitVersion")}")

    testImplementation(kotlin("test"))
}
