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
}
