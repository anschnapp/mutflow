package io.github.anschnapp.mutflow

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal actual fun <T> newConcurrentSet(): MutableSet<T> = ConcurrentHashMap.newKeySet()

internal actual fun <T> newSynchronizedList(): MutableList<T> = Collections.synchronizedList(mutableListOf())

actual fun <K, V> newConcurrentMap(): MutableMap<K, V> = ConcurrentHashMap()

actual fun currentThreadId(): Long = Thread.currentThread().id

internal actual fun <T> withSessionLock(lock: Any, block: () -> T): T = synchronized(lock, block)
