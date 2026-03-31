package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.VerificationMode
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests that LENIENT verification mode allows surviving mutations without failing.
 *
 * This test intentionally has weak coverage: it only tests isPositive(5) = true,
 * which means mutations like > → >= will survive. In STRICT mode this would fail,
 * but in LENIENT mode the test should pass with survivors only reported.
 */
@MutFlowTest(verificationMode = VerificationMode.LENIENT)
class VerificationModeLenientTest {

    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(5)
        }
        assertTrue(result, "isPositive(5) should be true")
    }
}
