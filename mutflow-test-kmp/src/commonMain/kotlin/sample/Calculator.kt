package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Sample class under mutation testing, shared across all KMP targets.
 *
 * Exercises the core operator families: arithmetic, relational comparison,
 * boolean logic, and control flow.
 */
@MutationTarget
class Calculator {

    fun add(a: Int, b: Int): Int = a + b

    fun isPositive(x: Int): Boolean = x > 0

    fun isInRange(x: Int): Boolean = x > 0 && x < 100

    fun max(a: Int, b: Int): Int = if (a > b) a else b

    fun startsWithA(s: String): Boolean = s.endsWith("A")

    fun normalized(s: String): String = s.trim()

    fun hasEven(xs: List<Int>): Boolean = xs.filter { it % 2 == 0 }.isNotEmpty()

    fun sameRef(a: Any, b: Any): Boolean = a === b

    fun notSameRef(a: Any, b: Any): Boolean = a !== b

    fun greet(name: String?): String = name ?: "guest"

    fun lengthOf(s: String?): Int? = s?.length

    fun emptyListReturn(): List<Int> {
        return listOf(1, 2, 3)
    }

    fun doubleThenSet(x: Int): Int {
        var result = 0
        result = x * 2
        return result
    }
}
