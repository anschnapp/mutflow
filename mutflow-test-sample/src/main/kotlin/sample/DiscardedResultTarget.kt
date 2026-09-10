package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Boolean calls in statement position with their result discarded. Inverting such a result
 * changes nothing observable, so no inversion mutation point may be generated for them.
 */
@MutationTarget
class DiscardedResultTarget {
    val items = mutableListOf<Int>()
    val flags = mutableListOf<Boolean>()

    /** `add` result discarded: no mutation point at all. */
    fun addDiscarded(x: Int) {
        items.add(x)
    }

    /** `add` result returned: the inversion point must stay. */
    fun addUsed(x: Int): Boolean = items.add(x)

    /** Outer `add` discarded, but the `>` inside its argument is still mutated. */
    fun addComparison(x: Int) {
        flags.add(x > 0)
    }

    /** Discarded inside a lambda body, where the compiler coerces the value to Unit. */
    fun addAllDiscarded(xs: List<Int>) {
        xs.forEach { items.add(it) }
    }

    /** `if` used as a statement: the call is a branch result, not a statement itself. */
    fun addInIf(c: Boolean) {
        if (c) items.add(1)
    }

    /** Subject `when` used as a statement: its branches sit inside a block holding the subject. */
    fun addInWhen(c: Int) {
        when (c) {
            1 -> items.add(1)
            else -> items.add(2)
        }
    }

    /** Safe call as a statement: the call is the value of the generated SAFE_CALL block. */
    fun addSafeCall(other: MutableList<Int>?) {
        other?.add(1)
    }

    /** `try` used as a statement: both the try result and the catch result are discarded. */
    fun addInTry(index: Int) {
        try {
            items.add(items[index])
        } catch (e: IndexOutOfBoundsException) {
            items.add(0)
        }
    }
}
