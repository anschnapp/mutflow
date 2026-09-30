package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * A loop that a mutation can make endless. The test's wall-clock budget runs out long
 * before the loop guard's own timeout, and the tight loop never looks at the interrupt.
 */
@MutationTarget
class LoopTarget {

    fun countTo(n: Long): Long {
        var i = 0L
        while (i < n) {
            i = i + 1
        }
        return i
    }
}
