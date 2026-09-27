package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Arithmetic on primitives whose operator has overloads for other operand types. A variant must
 * call the overload of the same signature: the first `minus` of `Int` takes a `Byte`, and
 * calling it with a `Long` or a `Double` computes a wrong value. `Char - Char` has no `Char +
 * Char` at all, and keeps the first `plus` of `Char`, `Char.plus(Int)`.
 */
@MutationTarget
class PrimitiveArithmeticTarget {

    fun distance(from: Char, to: Char): Int = to - from

    fun widened(a: Int, b: Long): Long = a + b

    fun fractional(a: Int, b: Double): Double = a + b
}
