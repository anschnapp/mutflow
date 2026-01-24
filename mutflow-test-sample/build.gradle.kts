import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm")
}

val compilerPluginJar = project(":mutflow-compiler-plugin").tasks.named("jar")

dependencies {
    implementation(project(":mutflow-core"))
    implementation(project(":mutflow-runtime"))

    // Add the compiler plugin JAR to the compiler classpath
    kotlinCompilerPluginClasspath(project(":mutflow-compiler-plugin"))

    testImplementation(project(":mutflow-junit6"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${property("junitVersion")}")
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(compilerPluginJar)
    compilerOptions {
        val pluginJarPath = compilerPluginJar.get().outputs.files.singleFile.absolutePath
        freeCompilerArgs.add("-Xplugin=$pluginJarPath")
    }
}

tasks.test {
    useJUnitPlatform()
}
