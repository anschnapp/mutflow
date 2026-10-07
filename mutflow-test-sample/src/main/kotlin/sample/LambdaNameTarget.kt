package sample

import io.github.anschnapp.mutflow.MutationTarget
import java.util.concurrent.Executor

/** Runs an action, as a Kotlin SAM type. */
fun interface Action {
    fun run()
}

/**
 * Unit lambdas whose body removal names them after what they are passed to or assigned to,
 * plus the cases that keep the `<anonymous>()` and function names.
 */
@MutationTarget
class LambdaNameTarget {
    val seen = mutableListOf<Int>()

    private val clearAll: () -> Unit = { seen.clear() }

    /** Passed to a call: named after the call. */
    fun recordEach(items: List<Int>) {
        items.forEach { seen.add(it) }
    }

    /** Two lambdas on one line: each gets its own name, so neither needs `#2`. */
    fun recordTwice(items: List<Int>) {
        items.onEach { seen.add(it) }.forEach { seen.add(-it) }
    }

    /** Converted to a Java interface for the call. */
    fun recordOnExecutor(executor: Executor) {
        executor.execute { seen.add(1) }
    }

    /** Converted to a Kotlin `fun interface` for the call. */
    fun recordThroughAction() {
        runAction { seen.add(2) }
    }

    /** Assigned to a local variable: named after the variable. */
    fun recordThroughVariable() {
        val record: (Int) -> Unit = { seen.add(it) }
        record(3)
    }

    /** Assigned to a property: named after the property. */
    fun clear() {
        clearAll()
    }

    /** Returned: nothing to name it after. */
    fun recorder(): () -> Unit = { seen.add(4) }

    /** A local function keeps its own name. */
    fun recordThroughLocalFunction() {
        fun record() {
            seen.add(5)
        }
        record()
    }

    private fun runAction(action: Action) = action.run()
}
