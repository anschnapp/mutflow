package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the variant of `Char - Char`, which has no operator of the same signature to swap to. */
class CharArithmeticTest {

    private val target = CharArithmeticTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `char minus char is still mutated to plus`() {
        val (distance, result) = MutationRegistry.withSession { target.distance('a', 'e') }
        assertEquals(4, distance)

        val point = result.discoveredPoints.single { it.pointId.contains("CharArithmeticTarget") }
        assertEquals(listOf("+"), point.variantOperators)
        val mutant = MutationRegistry.withSession(ActiveMutation(point.pointId, 0)) { target.distance('a', 'e') }.first
        assertEquals('e'.code + 'a'.code, mutant)
    }
}
