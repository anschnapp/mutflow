package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Target class with a range `for` loop.
 *
 * Kotlin lowers `for (i in 0 until n)` into a while loop whose body block must start with the
 * loop-variable declaration; ForLoopsLowering pattern-matches that shape. Injecting the loop
 * timeout check by wrapping the body used to break it and crash the compiler with
 * "No 'next' statement in for-loop", so this class simply has to compile and still be mutated.
 */
@MutationTarget
class RangeLoopTarget {

    fun sumBelow(n: Int): Int {
        var sum = 0
        for (i in 0 until n) {
            sum = sum + i
        }
        return sum
    }
}
