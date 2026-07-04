package io.github.anschnapp.mutflow

// Platform abstractions for MutationRegistry.
//
// These are `expect` declarations: common code compiles against these signatures,
// and each target provides an `actual` implementation (see Platform.jvm.kt).
// We deliberately use top-level `expect fun`s instead of `expect class`es -
// expect/actual *classes* are still in Beta and produce a compiler warning,
// while expect/actual functions are stable.
//
// The JVM actuals are the exact primitives the pre-KMP MutationRegistry used
// (System.nanoTime, synchronized, Collections.synchronizedList,
// ConcurrentHashMap.newKeySet), so JVM behavior is bit-identical.
// Native actuals arrive in Phase 2, where they can be much simpler because the
// per-process model has exactly one session and no parallel test classes.

/**
 * Monotonic nanosecond clock used for mutation timeout deadlines.
 */
internal expect fun nanoTime(): Long

/**
 * Runs [block] while holding the single global registry lock.
 * Ensures only one mutation session is active at a time even when
 * multiple test classes run in parallel.
 */
internal expect fun <T> withRegistryLock(block: () -> T): T

/**
 * A mutable list that is safe to append to from multiple threads.
 */
internal expect fun <E> threadSafeMutableListOf(): MutableList<E>

/**
 * A mutable set with atomic add semantics ([MutableSet.add] returns false
 * exactly once per element under concurrency).
 */
internal expect fun <E> threadSafeMutableSetOf(): MutableSet<E>
