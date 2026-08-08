package io.github.anschnapp.mutflow

/**
 * Platform-specific thread-safe collection factories used by [MutationRegistry].
 *
 * - JVM: backed by `java.util.concurrent` (real thread safety for parallel tests).
 * - JS: single-threaded, plain collections are sufficient.
 * - Native: plain collections; mutual exclusion is provided by the
 *   `synchronized(lock)` in [MutationRegistry.withSession], which guards all
 *   `check()` calls during a session.
 */
internal expect fun <T> newConcurrentSet(): MutableSet<T>

internal expect fun <T> newSynchronizedList(): MutableList<T>

/**
 * A thread-safe mutable map, used by the runtime for thread→session routing.
 * - JVM: backed by `java.util.concurrent.ConcurrentHashMap`.
 * - JS / WASM / Native: single-threaded, plain map is sufficient.
 */
expect fun <K, V> newConcurrentMap(): MutableMap<K, V>

/**
 * Returns an identifier for the current thread, used to route parameterless
 * `underTest()` calls to the right session.
 * - JVM: `Thread.currentThread().id`.
 * - JS / WASM / Native: a constant (single-threaded).
 */
expect fun currentThreadId(): Long

/**
 * Runs [block] while holding [lock], providing mutual exclusion for mutation
 * sessions. `synchronized` is JVM-only in Kotlin, so this is expect/actual.
 *
 * - JVM: real `synchronized` (parallel test classes).
 * - JS: single-threaded, no lock needed.
 * - Native: no-op for now; tests run sequentially. TODO(Phase 3): real mutex.
 */
internal expect fun <T> withSessionLock(lock: Any, block: () -> T): T
