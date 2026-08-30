package io.github.anschnapp.mutflow

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// JVM actuals for Platform.kt - verbatim the primitives the pre-KMP
// MutFlow / MutFlowSession used, so the JVM path behaves identically.

internal actual fun randomSessionIdValue(): String = UUID.randomUUID().toString()

internal actual fun currentThreadId(): Long = Thread.currentThread().id

internal actual fun <K, V> threadSafeMutableMapOf(): MutableMap<K, V> = ConcurrentHashMap()

internal actual fun generateSeed(): Long = System.currentTimeMillis() xor System.nanoTime()

// Normally null: the JUnit extension owns the run loop on the JVM, and the
// Native orchestration env vars (MUTFLOW_DISCOVERY_FILE etc.) must not change
// JVM behavior. See the expect declaration for details.
//
// The single exception is MUTFLOW_INACTIVE, which makes `underTest {}` a
// transparent pass-through. It exists for the STOCK test task of a
// multiplatform jvm() target: those sources live in commonTest and call
// underTest, but only the mutatedTest compilation carries the synthesized
// @MutFlowTest, so plain `./gradlew jvmTest` has no session and would fail on
// the missing-session guard. Native gets this for free (no MUTFLOW_* vars set
// means Inactive), and this makes the JVM behave the same way where the build
// says it should.
//
// The guard itself is deliberately kept for everyone else: in a plain
// kotlin("jvm") project a missing @MutFlowTest is a mistake, and silently
// running the block unmutated would hide it.
private val inactiveRun: ProcessRun? by lazy {
    if (System.getenv("MUTFLOW_INACTIVE")?.toBoolean() == true) {
        ProcessRun(ProcessRunMode.Inactive)
    } else {
        null
    }
}

internal actual fun currentProcessRun(): ProcessRun? = inactiveRun
