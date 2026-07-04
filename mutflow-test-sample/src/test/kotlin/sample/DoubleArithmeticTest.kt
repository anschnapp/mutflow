package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.*

/**
 * Regression test: mutated Double arithmetic must not lose precision.
 *
 * The mutation when-wrapper around IrCall nodes was typed Boolean regardless of
 * the original expression's type. For Double-valued expressions the JVM backend
 * then coerced the result, silently truncating fractional values even with no
 * mutation active (50.0 * 0.05 returned 2.0 instead of 2.5). Every other test
 * in this module uses integer-valued results, which is why only a fractional
 * result catches this.
 */
class DoubleArithmeticTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `fractional double result survives instrumentation outside a session`() {
        // No session at all: check() returns null, so the original code path runs.
        assertEquals(2.5, calculator.applyRate(50.0, 0.05))
    }

    @Test
    fun `fractional double result survives instrumentation in baseline`() {
        val result = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.applyRate(50.0, 0.05)
        }
        assertEquals(2.5, result)
    }
}
