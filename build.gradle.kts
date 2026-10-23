plugins {
    kotlin("jvm") apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    id("com.github.ben-manes.versions") version "0.66.0"
}

allprojects {
    group = "io.github.anschnapp.mutflow"
    version = findProperty("releaseVersion")?.toString() ?: "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // Publish against the lowest supported stdlib, so consumers keep their own 2.4.x
    // instead of being raised to the version mutflow happens to be built with.
    listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform").forEach { id ->
        plugins.withId(id) {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension> {
                coreLibrariesVersion = property("kotlinStdlibVersion").toString()
            }
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
}
