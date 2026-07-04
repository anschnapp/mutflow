package spike

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TEACHING NOTE: plain kotlin-test, no JUnit, no MutFlow.underTest {} - the
 * spike bypasses the runtime layer entirely (there is no native
 * mutflow-runtime yet; that's Phase 2). The stub registry treats the whole
 * process as one session, so simply executing Calculator inside any test is
 * enough to hit the injected MutationRegistry.check() calls.
 *
 * The tests are chosen so that every mutation of `x > 0` is caught by at
 * least one of them:
 *  - x >= 0  -> killed by zeroIsNotPositive (isPositive(0) becomes true)
 *  - x < 0   -> killed by positiveNumber (isPositive(5) becomes false)
 *  - x > 1   -> killed by oneIsPositive (isPositive(1) becomes false)
 *  - x > -1  -> killed by zeroIsNotPositive (isPositive(0) becomes true)
 *
 * "Killed" in the per-process model = the test binary exits nonzero.
 */
class CalculatorTest {

    private val calculator = Calculator()

    @Test
    fun positiveNumber() {
        assertTrue(calculator.isPositive(5))
    }

    @Test
    fun oneIsPositive() {
        // boundary test: kills the `0 -> 1` constant mutation
        assertTrue(calculator.isPositive(1))
    }

    @Test
    fun zeroIsNotPositive() {
        // boundary test: kills the `>` -> `>=` mutation
        assertFalse(calculator.isPositive(0))
    }

    @Test
    fun negativeNumber() {
        assertFalse(calculator.isPositive(-5))
    }
}
