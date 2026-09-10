package io.github.anschnapp.mutflow

// Platform abstractions for MutFlow / MutFlowSession.
//
// Same pattern as mutflow-core's Platform.kt: top-level `expect fun`s (stable,
// unlike expect/actual classes which are still Beta), with JVM actuals that are
// verbatim the primitives the pre-KMP code used. Note that mutflow-core's
// helpers are `internal` and therefore not visible here even though both
// modules share a package - each module declares its own.
//
// This file is deliberately NOT named Platform.kt: core and runtime share the
// same package, and two files with the same name would compile to the same JVM
// facade class (Platform_jvmKt). At runtime one would shadow the other on the
// classpath, producing NoSuchMethodError for the shadowed module's helpers.
//
// On Native most of these actuals collapse to trivial implementations,
// because one process hosts exactly one session (no parallel test classes,
// no thread routing).

/**
 * Generates a unique value for a new [SessionId].
 * JVM: a random UUID string.
 */
internal expect fun randomSessionIdValue(): String

/**
 * Identifies the current thread, used to route the parameterless
 * `MutFlow.underTest {}` call to the session whose run started on this thread.
 */
internal expect fun currentThreadId(): Long

/**
 * A mutable map safe for concurrent access from multiple test threads.
 */
internal expect fun <K, V> threadSafeMutableMapOf(): MutableMap<K, V>

/**
 * Generates a fresh random seed for [Shuffle.PerRun] selection.
 */
internal expect fun generateSeed(): Long

/**
 * Returns the process-level run for the Native orchestration path, or null
 * if `underTest {}` should use the JVM session machinery instead.
 *
 * JVM: null - the JUnit extension owns the run loop, and the MUTFLOW_*
 * orchestration env vars are deliberately ignored so JVM behavior is
 * bit-identical to pre-Native releases. The one exception is
 * MUTFLOW_INACTIVE, which yields a pass-through run for the stock test task
 * of a multiplatform jvm() target.
 * Native: never null - one ProcessRun per process, resolved lazily from the
 * environment (Inactive when no MUTFLOW_* vars are set, so plain test runs
 * work without any orchestrator).
 */
internal expect fun currentProcessRun(): ProcessRun?

/**
 * Reads an environment variable, or null if unset. Backs the `MUTFLOW_*`
 * overrides that common code resolves itself (see [TestBudget.fromEnvironment]).
 */
internal expect fun environmentVariable(name: String): String?

/**
 * A pending interruption of the thread that scheduled it; see [scheduleInterrupt].
 */
internal interface TestInterrupt {
    /**
     * Cancels the interruption. Returns whether it had already fired, in which
     * case the calling thread's interrupt status is cleared so nothing leaks
     * into the next test.
     */
    fun cancel(): Boolean
}

/**
 * Schedules an interruption of the calling thread [delayMs] from now, unless
 * cancelled first. Because interrupted code may swallow the interrupt and keep
 * waiting, the interruption repeats until [cancel]; if the thread has still not
 * returned [graceMs] after the first interrupt, [onAbandoned] runs on the
 * watchdog thread (a grace of 0 disables that).
 *
 * JVM: a daemon watchdog thread and `Thread.interrupt()`. Native: never fires -
 * the test process is single-threaded and one process hosts one run, so the
 * Gradle orchestrator's hard process timeout plays this role there.
 */
internal expect fun scheduleInterrupt(delayMs: Long, graceMs: Long, onAbandoned: () -> Unit): TestInterrupt
