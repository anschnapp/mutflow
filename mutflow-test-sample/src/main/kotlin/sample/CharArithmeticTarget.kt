package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * `Char - Char` is an `Int`, and `Char` has no `plus(Char)`: its variant is `Char.plus(Int)`,
 * the first `plus` of the class, which the replacement lookup keeps when no operator of the
 * same signature exists.
 */
@MutationTarget
class CharArithmeticTarget {

    fun distance(from: Char, to: Char): Int = to - from
}
