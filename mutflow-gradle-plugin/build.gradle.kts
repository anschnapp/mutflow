plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${property("kotlinVersion")}")

    testImplementation(kotlin("test"))
}

// Generate version constant at build time
val generatedSrcDir = layout.buildDirectory.dir("generated/src/main/kotlin")

sourceSets {
    main {
        kotlin.srcDir(generatedSrcDir)
    }
}

val generateVersionFile by tasks.registering {
    val outputDir = generatedSrcDir
    val projectVersion = project.version.toString()

    outputs.dir(outputDir)

    doLast {
        val file = outputDir.get().file("io/github/anschnapp/mutflow/gradle/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("""
            package io.github.anschnapp.mutflow.gradle

            internal const val MUTFLOW_VERSION = "$projectVersion"
        """.trimIndent())
    }
}

tasks.named("compileKotlin") {
    dependsOn(generateVersionFile)
}

gradlePlugin {
    plugins {
        create("mutflow") {
            id = "io.github.anschnapp.mutflow"
            implementationClass = "io.github.anschnapp.mutflow.gradle.MutflowGradlePlugin"
            displayName = "Mutflow Mutation Testing Plugin"
            description = "Lightweight mutation testing for Kotlin"
        }
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)

    // Only sign when credentials are available (CI environment)
    if (project.hasProperty("signingInMemoryKey") || System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

    pom {
        name.set("mutflow-gradle-plugin")
        description.set("Gradle plugin for Mutflow - Lightweight mutation testing for Kotlin")
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
                name.set("anschnapp")
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
