package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** An operator of a class with no counterpart of the same signature is not mutated. */
class ClassOperatorTest {

    private val target = ClassOperatorTarget()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `vec plus vec has no mutation point`() {
        val (sum, result) = MutationRegistry.withSession { target.add(Vec(1), Vec(2)) }
        assertEquals(Vec(3), sum)
        assertEquals(emptyList(), result.discoveredPoints.filter { it.pointId.contains("ClassOperatorTarget") })
    }
}
