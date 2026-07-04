package io.github.anschnapp.mutflow

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// JVM actuals for Platform.kt - verbatim the primitives the pre-KMP
// MutFlow / MutFlowSession used, so the JVM path behaves identically.

internal actual fun randomSessionIdValue(): String = UUID.randomUUID().toString()

internal actual fun currentThreadId(): Long = Thread.currentThread().id

internal actual fun <K, V> threadSafeMutableMapOf(): MutableMap<K, V> = ConcurrentHashMap()

internal actual fun generateSeed(): Long = System.currentTimeMillis() xor System.nanoTime()
