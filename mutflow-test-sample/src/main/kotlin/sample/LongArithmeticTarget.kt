package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Long arithmetic chains. Every arithmetic variant used to copy both operands, and the left
 * operand of `a + b + c` is the already instrumented `a + b`, so each term doubled the code:
 * twelve terms no longer fit in a JVM method ("Method too large"). Mutating over hoisted
 * operands keeps the growth linear.
 */
@MutationTarget
class LongArithmeticTarget {

    fun sum(
        a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int,
        a9: Int, a10: Int, a11: Int, a12: Int, a13: Int, a14: Int, a15: Int, a16: Int
    ): Int = a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15 + a16

    /** Mixed operators and parentheses, so the chain nests to the right as well. */
    fun weighted(
        a1: Int, a2: Int, a3: Int, a4: Int, a5: Int, a6: Int, a7: Int, a8: Int
    ): Int = a1 * 2 + (a2 * 3 - (a3 * 4 + (a4 * 5 - (a5 * 6 + (a6 * 7 - (a7 * 8 + a8 * 9))))))
}
