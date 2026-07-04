// ============================================================================
// TEACHING NOTE: a minimal Kotlin Multiplatform (KMP) library build
// ----------------------------------------------------------------------------
// The `kotlin("multiplatform")` plugin replaces the familiar `kotlin("jvm")`.
// Instead of one compilation, a KMP project declares a set of TARGETS
// (platforms), and Gradle creates compilation + packaging tasks per target.
//
// The output of a Kotlin/Native *library* compilation is a `.klib` file -
// serialized Kotlin IR plus metadata, NOT machine code. Machine code is only
// produced at the final LINK step of an executable/test binary (in :app).
// That's also why compile is relatively fast and link is the slow part on
// Native.
// ============================================================================

plugins {
    kotlin("multiplatform") // version comes from settings.gradle.kts pluginManagement
}

kotlin {
    // Declaring a target is what creates all the machinery for that platform.
    // `linuxX64()` alone gives us (among others) these tasks:
    //   compileKotlinLinuxX64     -> produces the .klib for this library
    // We only need linuxX64 for the spike because that's the build host
    // (your machine). Adding e.g. `macosArm64()` here would just fail to be
    // buildable on Linux - KMP targets can only be built on a matching host.
    linuxX64()

    // ------------------------------------------------------------------------
    // TEACHING NOTE: source sets
    // ------------------------------------------------------------------------
    // KMP organizes code into SOURCE SETS that form a hierarchy:
    //
    //   commonMain          (pure common Kotlin, no platform APIs)
    //      └── nativeMain   (shared by all native targets; created
    //           │            automatically by the "default hierarchy template")
    //           └── linuxX64Main   (this exact target; full platform API access)
    //
    // Code in commonMain can only use APIs available on EVERY declared target.
    // Our stub needs `platform.posix.getenv` - a platform API that does not
    // exist in common code - so the stub source lives in src/linuxX64Main/
    // (see directory layout) rather than src/commonMain/.
    //
    // We don't need to configure anything here explicitly: source sets are
    // wired up by convention from the directory names. This empty-ish block
    // exists only to host this comment.
    // ------------------------------------------------------------------------
    sourceSets {
        // nothing to configure - src/linuxX64Main/kotlin is picked up by convention
    }
}
