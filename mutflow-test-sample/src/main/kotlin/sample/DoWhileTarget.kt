package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * A do-while whose condition reads a value declared in the body. Kotlin allows it, and the
 * loop guard must be injected inside the body block, not around it, or the condition loses
 * sight of the variable and the backend fails with "No mapping for symbol".
 */
@MutationTarget
class DoWhileTarget {

    /** Drains the iterator into a list, the way a zip-entry loop reads until null. */
    fun drain(source: Iterator<Int?>): List<Int> {
        val seen = mutableListOf<Int>()
        do {
            val next = if (source.hasNext()) source.next() else null
            if (next != null && next > 0) seen.add(next)
        } while (next != null)
        return seen
    }
}
