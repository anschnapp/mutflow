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
// Native actuals arrive in Phase 2. On Native most of these collapse to
// trivial implementations because one process hosts exactly one session
// (no parallel test classes, no thread routing).

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
