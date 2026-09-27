package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins what the single variant of each primitive operator computes. */
class PrimitiveArithmeticTest {

    private val target = PrimitiveArithmeticTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    /** Runs [block] once per variant of every point [block] reaches, returning each result. */
    private fun <T> everyVariant(block: () -> T): List<Pair<String, T>> {
        val (_, result) = MutationRegistry.withSession { block() }
        return result.discoveredPoints.filter { it.pointId.contains("PrimitiveArithmeticTarget") }.flatMap { point ->
            List(point.variantCount) { variant ->
                val mutant = MutationRegistry.withSession(ActiveMutation(point.pointId, variant)) { block() }.first
                "${point.originalOperator} → ${point.variantOperators[variant]}" to mutant
            }
        }
    }

    @Test
    fun `char minus char is mutated to plus`() {
        assertEquals(4, target.distance('a', 'e'))
        assertEquals(listOf("- → +" to 'e'.code + 'a'.code), everyVariant { target.distance('a', 'e') })
    }

    @Test
    fun `int plus long is mutated to the minus taking a long`() {
        assertEquals(5_000_000_001L, target.widened(1, 5_000_000_000L))
        assertEquals(listOf("+ → -" to -4_999_999_999L), everyVariant { target.widened(1, 5_000_000_000L) })
    }

    @Test
    fun `int plus double is mutated to the minus taking a double`() {
        assertEquals(1.5, target.fractional(1, 0.5))
        assertEquals(listOf("+ → -" to 0.5), everyVariant { target.fractional(1, 0.5) })
    }
}
