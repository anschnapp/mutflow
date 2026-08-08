package io.github.anschnapp.mutflow

// JS is single-threaded: plain collections are sufficient.
internal actual fun <T> newConcurrentSet(): MutableSet<T> = mutableSetOf()

internal actual fun <T> newSynchronizedList(): MutableList<T> = mutableListOf()

actual fun <K, V> newConcurrentMap(): MutableMap<K, V> = mutableMapOf()

actual fun currentThreadId(): Long = 0L

internal actual fun <T> withSessionLock(lock: Any, block: () -> T): T = block()
