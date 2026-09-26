package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the variants of operators declared as extensions inside an object, activating each point
 * directly instead of going through selection so the assertions are about one known mutant.
 *
 * Compiling [MemberExtensionOperatorTarget] is the first regression. The second is what a
 * variant computes: it must apply the swapped operator of [MoneyOperators] to the same two
 * operands, so every point here has exactly one variant, whose result is pinned.
 */
class MemberExtensionOperatorTest {

    private val target = MemberExtensionOperatorTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
        target.reset()
    }

    /** Runs [block] once per variant of every point [block] reaches, returning each result. */
    private fun <T> everyVariant(block: () -> T): List<Pair<String, T>> {
        val (_, result) = MutationRegistry.withSession { block() }
        return result.discoveredPoints.filter { it.pointId.contains("MemberExtensionOperatorTarget") }.flatMap { point ->
            List(point.variantCount) { variant ->
                target.reset()
                val mutant = MutationRegistry.withSession(ActiveMutation(point.pointId, variant)) { block() }.first
                "${point.originalOperator} → ${point.variantOperators[variant]}" to mutant
            }
        }
    }

    @Test
    fun `the originals are unchanged while no mutation is active`() {
        val results = MutationRegistry.withSession {
            listOf(
                target.total(500, 200),
                target.difference(Money(500), Money(200)),
                target.scaled(Money(500), 4),
                target.share(Money(500), 4),
                target.remainder(Money(500), 3),
                target.tipped(Money(500), 20)
            )
        }.first
        assertEquals(listOf(Money(700), Money(300), Money(2000), Money(125), Money(2), Money(520)), results)
    }

    @Test
    fun `plus through with is mutated to the minus of money`() {
        assertEquals(listOf("+ → -" to Money(300)), everyVariant { target.total(500, 200) })
    }

    @Test
    fun `the operands are evaluated once and in order by the original and the variant`() {
        MutationRegistry.withSession { target.total(500, 200) }
        assertEquals(listOf(500L, 200L), target.evaluated, "original")

        everyVariant { target.total(500, 200) }
        assertEquals(listOf(500L, 200L), target.evaluated, "variant")
    }

    @Test
    fun `minus through an import is mutated to the plus of money`() {
        assertEquals(listOf("- → +" to Money(700)), everyVariant { target.difference(Money(500), Money(200)) })
    }

    @Test
    fun `times is mutated to the div of money`() {
        assertEquals(listOf("* → /" to Money(125)), everyVariant { target.scaled(Money(500), 4) })
    }

    @Test
    fun `div is mutated to the times of money`() {
        assertEquals(listOf("/ → *" to Money(2000)), everyVariant { target.share(Money(500), 4) })
    }

    @Test
    fun `rem is mutated to the div of money`() {
        assertEquals(listOf("% → /" to Money(166)), everyVariant { target.remainder(Money(500), 3) })
    }

    @Test
    fun `an operator without a counterpart of the same signature is not mutated`() {
        assertEquals(emptyList(), everyVariant { target.tipped(Money(500), 20) })
    }
}
