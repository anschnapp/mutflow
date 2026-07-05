package io.github.anschnapp.mutflow

// Native actuals for Platform.kt, shared by all native targets (this file
// lives in the nativeMain source set that the default hierarchy template
// creates above linuxX64/mingwX64).
//
// TEACHING NOTE: why these are so much simpler than the JVM actuals.
// On the JVM, one process hosts many test classes that may run in parallel,
// so the registry needs a real lock and concurrent collections. On Native,
// the design's process-per-mutation model means one process = one session,
// and the kotlin-test runner executes tests sequentially on a single thread.
// The whole "concurrency" problem class does not exist here, so the actuals
// collapse to plain implementations (DESIGN-KOTLIN-NATIVE.md calls this out
// as a simplification, not a workaround).
//
// `platform.posix.*` is the C POSIX API exposed to Kotlin/Native via the
// built-in cinterop bindings. Because this source set is shared between
// linux and mingw, the compiler only lets us use the *commonized* subset -
// functions that exist with compatible signatures on both. getenv/fopen/
// fputs/fclose are all in that subset. The foreign-function API is still
// marked experimental, hence the opt-ins.

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.time.TimeSource
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

// Origin for the monotonic clock. kotlin.system.getTimeNanos() is deprecated,
// so we anchor a kotlin.time monotonic mark at process start (first access)
// and report nanoseconds elapsed since then. The registry only ever compares
// nanoTime() values against each other (deadline arithmetic), so an arbitrary
// origin is fine - exactly like System.nanoTime(), whose origin is also
// unspecified.
private val timeOrigin = TimeSource.Monotonic.markNow()

internal actual fun nanoTime(): Long = timeOrigin.elapsedNow().inWholeNanoseconds

// No lock needed: single-threaded test process, one session at a time.
internal actual fun <T> withRegistryLock(block: () -> T): T = block()

internal actual fun <E> threadSafeMutableListOf(): MutableList<E> = mutableListOf()

internal actual fun <E> threadSafeMutableSetOf(): MutableSet<E> = mutableSetOf()

@OptIn(ExperimentalForeignApi::class)
internal actual fun getEnvVar(name: String): String? =
    // getenv() returns a raw C string pointer (CPointer<ByteVar>?);
    // toKString() copies it into a Kotlin String.
    getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeTextFile(path: String, content: String) {
    // "wb" = write + binary. The binary flag matters on Windows (mingw):
    // text mode would rewrite \n as \r\n, and we want the discovery/result
    // files byte-identical across platforms.
    val file = fopen(path, "wb")
        ?: error("[mutflow] Failed to open file for writing: $path")
    try {
        if (fputs(content, file) < 0) {
            error("[mutflow] Failed to write file: $path")
        }
    } finally {
        fclose(file)
    }
}
