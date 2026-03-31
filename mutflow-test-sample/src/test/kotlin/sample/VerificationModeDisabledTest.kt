package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.VerificationMode
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests that DISABLED verification mode skips mutation runs entirely.
 *
 * Only the baseline run should execute. This test has weak coverage that would
 * produce surviving mutations, but since mutation runs are skipped, it passes.
 */
@MutFlowTest(verificationMode = VerificationMode.DISABLED)
class VerificationModeDisabledTest {

    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(5)
        }
        assertTrue(result, "isPositive(5) should be true")
    }
}
