package spike

import io.github.anschnapp.mutflow.MutFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TEACHING NOTE (Phase 2): plain kotlin-test, no JUnit, but now with the real
 * multiplatform `MutFlow.underTest {}` - the exact authoring model from
 * DESIGN-KOTLIN-NATIVE.md ("Test Authoring in commonTest"). On native,
 * underTest delegates to the process-level run resolved from the MUTFLOW_*
 * env vars:
 *
 *  - no env vars            -> pass-through (plain `:app:linuxX64Test` is green)
 *  - MUTFLOW_DISCOVERY_FILE -> each underTest block runs in a discovery
 *                              session; points + touch counts land in the file
 *  - MUTFLOW_ACTIVE_MUTATION-> the mutation is active INSIDE these blocks,
 *                              and killing it = assertion throws = binary
 *                              exits nonzero (exit-code inversion happens in
 *                              the Phase-3 Gradle task)
 *
 * The tests are chosen so that every mutation of `x > 0` is caught by at
 * least one of them:
 *  - x >= 0  -> killed by zeroIsNotPositive (isPositive(0) becomes true)
 *  - x < 0   -> killed by positiveNumber (isPositive(5) becomes false)
 *  - x > 1   -> killed by oneIsPositive (isPositive(1) becomes false)
 *  - x > -1  -> killed by zeroIsNotPositive (isPositive(0) becomes true)
 */
class CalculatorTest {

    private val calculator = Calculator()

    @Test
    fun positiveNumber() {
        val result = MutFlow.underTest { calculator.isPositive(5) }
        assertTrue(result)
    }

    @Test
    fun oneIsPositive() {
        // boundary test: kills the `0 -> 1` constant mutation
        val result = MutFlow.underTest { calculator.isPositive(1) }
        assertTrue(result)
    }

    @Test
    fun zeroIsNotPositive() {
        // boundary test: kills the `>` -> `>=` mutation
        val result = MutFlow.underTest { calculator.isPositive(0) }
        assertFalse(result)
    }

    @Test
    fun negativeNumber() {
        val result = MutFlow.underTest { calculator.isPositive(-5) }
        assertFalse(result)
    }
}
