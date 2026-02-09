plugins {
    kotlin("jvm") apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
    id("com.github.ben-manes.versions") version "0.53.0"
}

allprojects {
    group = "io.github.anschnapp.mutflow"
    version = findProperty("releaseVersion")?.toString() ?: "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
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
