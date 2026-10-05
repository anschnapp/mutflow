package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Chains that nest left and right at the same time. The truth tables are enumerated in full,
 * so any mutant that changes the function for any input is killed, whichever operand slot the
 * mutated operator sits in.
 */
@MutFlowTest
class MixedAssociativityTargetTest {

    private val target = MixedAssociativityTarget()

    /** All 2^n tuples of booleans, in a stable order. */
    private fun tuples(n: Int): List<List<Boolean>> =
        (0 until (1 shl n)).map { row -> List(n) { bit -> (row shr bit) and 1 == 1 } }

    @Test
    fun `groups joined by and match their truth table`() {
        val rows = tuples(6)
        val expected = rows.map { (a, b, c, d, e, f) -> (a || b) && (c || d) && (e || f) }

        val actual = MutFlow.underTest {
            rows.map { (a, b, c, d, e, f) -> target.allGroupsSatisfied(a, b, c, d, e, f) }
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `alternating operators match their truth table`() {
        val rows = tuples(5)
        val expected = rows.map { (a, b, c, d, e) -> a && (b || (c && (d || e))) }

        val actual = MutFlow.underTest {
            rows.map { (a, b, c, d, e) -> target.nestedAlternating(a, b, c, d, e) }
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `the null guard holds and the bounds are exact`() {
        // "ninechars" is 9 characters and "tenletters" is 10, so both sides of `length < 10`
        // are pinned; the padded name pins the trim comparison; null pins the guard, which
        // also throws if a mutant stops short-circuiting.
        val names = listOf(null, "ninechars", "tenletters", "elevenchars", " padded ", "")
        val expected = listOf(false, true, false, false, false, true)

        val actual = MutFlow.underTest { names.map { target.nameIsShortAndTrimmed(it) } }

        assertEquals(expected, actual)
    }

    @Test
    fun `a chain in a loop condition governs every pass`() {
        // 0 pins the case where the loop never runs, 3 the left operand and 10 the right one.
        val counts = MutFlow.underTest { listOf(0, 3, 10).map { target.countWhileBothHold(it) } }

        assertEquals(listOf(0, 3, 5), counts)
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
