package io.github.anschnapp.mutflow

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

internal actual fun environmentVariable(name: String): String? = System.getenv(name)

// One daemon thread for every budget in the JVM: a scheduled task per test is
// cheap, and daemon means a forgotten handle can never keep the JVM alive.
private val watchdog: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mutflow-watchdog").apply { isDaemon = true }
    }
}

// How often the interrupt is repeated while waiting for the test to return.
private const val REPEAT_INTERVAL_MS = 100L

internal actual fun scheduleInterrupt(delayMs: Long, graceMs: Long, onAbandoned: () -> Unit): TestInterrupt =
    JvmTestInterrupt(Thread.currentThread(), graceMs, onAbandoned).also { it.schedule(delayMs) }

private class JvmTestInterrupt(
    private val target: Thread,
    private val graceMs: Long,
    private val onAbandoned: () -> Unit
) : TestInterrupt {
    private val lock = Any()
    private var cancelled = false
    private var fired = false
    private var firstInterruptNanos = 0L
    private var pending: ScheduledFuture<*>? = null

    fun schedule(delayMs: Long) {
        synchronized(lock) {
            if (!cancelled) pending = watchdog.schedule(::fire, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun fire() {
        val abandoned = synchronized(lock) {
            if (cancelled) return
            if (!fired) {
                fired = true
                firstInterruptNanos = System.nanoTime()
            }
            target.interrupt()
            val sinceFirstMs = (System.nanoTime() - firstInterruptNanos) / 1_000_000
            graceMs > 0 && sinceFirstMs >= graceMs
        }
        if (abandoned) onAbandoned() else schedule(REPEAT_INTERVAL_MS)
    }

    // Interrupting and cancelling happen under the same lock, so once cancel()
    // returns no interrupt can arrive late and hit the next test.
    override fun cancel(): Boolean = synchronized(lock) {
        cancelled = true
        pending?.cancel(false)
        if (fired && Thread.currentThread() === target) Thread.interrupted()
        fired
    }
}

internal actual fun terminateProcess(status: Int): Nothing = kotlin.system.exitProcess(status)
