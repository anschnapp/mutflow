package io.github.anschnapp.mutflow

// Mutual exclusion is provided by the lock in MutationRegistry.withSession,
// which guards all check() calls during a session. Plain collections are sufficient.
internal actual fun <T> newConcurrentSet(): MutableSet<T> = mutableSetOf()

internal actual fun <T> newSynchronizedList(): MutableList<T> = mutableListOf()

actual fun <K, V> newConcurrentMap(): MutableMap<K, V> = mutableMapOf()

actual fun currentThreadId(): Long = 0L

// TODO(Phase 3): real mutex for Native. Tests run sequentially for now.
internal actual fun <T> withSessionLock(lock: Any, block: () -> T): T = block()
