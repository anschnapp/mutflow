import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform")
}

val compilerPluginJar = project(":mutflow-compiler-plugin").tasks.named("jar")

@OptIn(ExperimentalWasmDsl::class)
kotlin {
    jvm()
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
    linuxX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":mutflow-annotations"))
            implementation(project(":mutflow-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Apply the mutflow compiler plugin to every compilation (production + test).
// The plugin only injects mutations into @MutationTarget classes, so production
// code is unaffected unless explicitly annotated.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(compilerPluginJar)
    compilerOptions {
        val pluginJarPath = compilerPluginJar.get().outputs.files.singleFile.absolutePath
        freeCompilerArgs.add("-Xplugin=$pluginJarPath")
        // Verify the injected IR is well-formed on every backend (PLAN.md Phase 2).
        freeCompilerArgs.add("-Xverify-ir=error")
    }
}
