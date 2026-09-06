// ============================================================================
// Opt-in extra Kotlin/Native targets, requested via a Gradle property.
//
// WHY THIS EXISTS
//
// The native targets declared in the three KMP modules (mutflow-annotations,
// mutflow-core, mutflow-runtime) are the ones we publish to Maven Central, and
// in Kotlin Multiplatform "published" and "supported" are the same thing: a
// consumer can only depend on a KMP library whose target set is a superset of
// its own. A project declaring macosArm64() against a mutflow that does not
// publish macosArm64 gets a hard dependency-resolution failure.
//
// The set we publish is deliberately small (see mutflow-core/build.gradle.kts
// for why), but nothing in the source stands in the way of more: KGP's default
// hierarchy template files every native target under nativeMain, so the
// existing posix-based actuals in src/nativeMain apply unchanged, and Apple
// klibs cross-compile from Linux without a Mac.
//
// So a developer who wants a target we do not publish can build it themselves.
// This script means they do not have to hand-edit three build files (and get
// the three lists out of sync, which silently breaks the KMP subset rule):
//
//     ./gradlew publishToMavenLocal -Pmutflow.extraNativeTargets=macosArm64
//
// and then consume mutflow from mavenLocal() in their own project.
//
// HOW IT IS WIRED
//
// Each of the three KMP modules applies this script at the end of its build
// file, after its own kotlin { } block has declared the baseline targets.
// `apply(from = ...)` runs the script with `this` bound to that module's
// Project. Every module reads the same property, so all three necessarily end
// up with the same target set - which is exactly the invariant the KMP subset
// rule needs.
//
// WHY REFLECTION
//
// An `apply(from = ...)` script does NOT inherit the applying project's
// buildscript classpath, so the Kotlin Gradle plugin's types are not
// resolvable here: no `import KotlinMultiplatformExtension`, no typed
// `kotlin.macosArm64()`. Declaring KGP in a buildscript { } block of our own
// would be worse than useless - it would load a SECOND copy of those classes
// in a separate classloader, and the extension we then looked up would not be
// an instance of them.
//
// Reflection sidesteps the whole problem, and turns out to be the better fit
// anyway. KGP declares each target as a real zero-argument method on the
// extension (verified with javap: `public abstract ... macosArm64()`), named
// exactly like the target. So a name from the property IS the method name,
// there is no hardcoded list of targets to maintain, and a target added by a
// future Kotlin version works here on the day it ships.
//
// The alternative to all of this was a buildSrc precompiled script plugin,
// which would be typed but adds a whole extra build to the project for
// ~30 lines of glue.
// ============================================================================

val propertyName = "mutflow.extraNativeTargets"

// findProperty picks up -P flags, gradle.properties and ORG_GRADLE_PROJECT_
// env vars alike, so any of the usual ways of setting it works.
val requested: List<String> = (findProperty(propertyName) as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    .orEmpty()

if (requested.isNotEmpty()) {
    // Looked up by name rather than by type, for the classpath reason above.
    val kotlinExtension = extensions.getByName("kotlin")

    requested.forEach { targetName ->
        val method = try {
            kotlinExtension.javaClass.getMethod(targetName)
        } catch (e: NoSuchMethodException) {
            error(
                "Unknown target '$targetName' in -P$propertyName. " +
                    "The value must be a Kotlin target function name such as " +
                    "macosArm64, macosX64, iosSimulatorArm64 or linuxArm64."
            )
        }

        // Guard against non-native targets. `jvm` and `js` are zero-arg methods
        // on the same extension, so without this check they would be accepted
        // and then fail much later and much less clearly: this project has
        // expect/actual implementations for JVM and native only, so a js()
        // target has no actuals for Platform.kt and cannot compile at all.
        //
        // Every native target function returns a KotlinNativeTarget or a
        // subtype of it (KotlinNativeTargetWithHostTests for macosArm64,
        // KotlinNativeTargetWithSimulatorTests for the simulators), so the
        // return type name is a reliable and version-stable discriminator.
        if (!method.returnType.name.contains("KotlinNativeTarget")) {
            error(
                "'$targetName' is not a Kotlin/Native target. Only native targets can be " +
                    "added via -P$propertyName: mutflow's expect/actual implementations " +
                    "cover JVM and native, so no other platform would compile."
            )
        }

        method.invoke(kotlinExtension)
    }

    // Worth a lifecycle line rather than an info one: this changes what the
    // build publishes, and a silent difference between a local build and a
    // released artifact is exactly the kind of thing that wastes an afternoon.
    logger.lifecycle(
        "[mutflow] ${project.name}: adding native target(s) ${requested.joinToString(", ")} " +
            "on top of the published set"
    )
}
