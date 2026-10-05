import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val compilerPluginJar = project(":mutflow-compiler-plugin").tasks.named("jar")

dependencies {
    implementation(project(":mutflow-core"))
    implementation(project(":mutflow-runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serializationVersion")}")

    // Add the compiler plugin JAR to the compiler classpath
    kotlinCompilerPluginClasspath(project(":mutflow-compiler-plugin"))

    testImplementation(project(":mutflow-junit6"))
    testImplementation(project(":mutflow-junit4"))
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:${property("junitVersion")}")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${property("junitVersion")}")
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(compilerPluginJar)
    compilerOptions {
        val pluginJarPath = compilerPluginJar.get().outputs.files.singleFile.absolutePath
        freeCompilerArgs.add("-Xplugin=$pluginJarPath")
        // The pattern route to a file's top-level declarations; PatternTopLevelTarget.kt carries no annotation.
        freeCompilerArgs.addAll("-P", "plugin:io.github.anschnapp.mutflow:target=sample.PatternTopLevelTargetKt")
        // A @file:JvmMultifileClass facade: the pattern names the facade, the two parts get ids of their own.
        freeCompilerArgs.addAll("-P", "plugin:io.github.anschnapp.mutflow:target=sample.MultifileTarget")
        // A @Serializable class and, through `.**`, the serializer object generated inside it.
        freeCompilerArgs.addAll("-P", "plugin:io.github.anschnapp.mutflow:target=sample.SerializableTarget")
        freeCompilerArgs.addAll("-P", "plugin:io.github.anschnapp.mutflow:target=sample.SerializableTarget.**")
        // mutflow only sees what the serialization plugin generates when it runs after it, as it
        // does in a build that passes it with -Xplugin alone.
        freeCompilerArgs.add("-Xcompiler-plugin-order=org.jetbrains.kotlinx.serialization>io.github.anschnapp.mutflow")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
