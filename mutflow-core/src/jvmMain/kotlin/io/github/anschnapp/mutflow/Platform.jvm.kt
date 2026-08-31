package io.github.anschnapp.mutflow

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// JVM actuals for Platform.kt. These are verbatim the primitives the
// pre-KMP MutationRegistry used, so the JVM path behaves identically.

internal actual fun nanoTime(): Long = System.nanoTime()

// Same single global lock object the registry had before the KMP split;
// it only moved from a field inside the MutationRegistry object to this file.
private val registryLock = Any()

internal actual fun <T> withRegistryLock(block: () -> T): T =
    synchronized(registryLock) { block() }

internal actual fun <E> threadSafeMutableListOf(): MutableList<E> =
    Collections.synchronizedList(mutableListOf())

internal actual fun <E> threadSafeMutableSetOf(): MutableSet<E> =
    ConcurrentHashMap.newKeySet()

// The two actuals below exist to satisfy the expect declarations for the JVM
// target; the JVM orchestration path (JUnit extension) does not use them.
// Only the Native path reads env vars and writes discovery/result files.

internal actual fun getEnvVar(name: String): String? = System.getenv(name)

internal actual fun writeTextFile(path: String, content: String) {
    java.io.File(path).writeText(content)
}
