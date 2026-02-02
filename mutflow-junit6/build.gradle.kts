plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(project(":mutflow-runtime"))

    implementation("org.junit.jupiter:junit-jupiter-api:${property("junitVersion")}")
    implementation("org.junit.jupiter:junit-jupiter-engine:${property("junitVersion")}")

    testImplementation(kotlin("test"))
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)

    // Only sign when credentials are available (CI environment)
    if (project.hasProperty("signingInMemoryKey") || System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null) {
        signAllPublications()
    }

    pom {
        name.set("mutflow-junit6")
        description.set("JUnit 6 integration for Mutflow - Lightweight mutation testing for Kotlin")
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
