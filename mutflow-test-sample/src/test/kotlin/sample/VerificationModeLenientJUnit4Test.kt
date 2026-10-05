package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.VerificationMode
import io.github.anschnapp.mutflow.junit4.MutFlowRunner
import io.github.anschnapp.mutflow.junit4.MutFlowTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * The JUnit 4 counterpart of [VerificationModeLenientTest]: deliberately weak coverage, so
 * mutations such as `> → >=` survive; LENIENT mode only reports them instead of failing.
 */
@RunWith(MutFlowRunner::class)
@MutFlowTest(verificationMode = VerificationMode.LENIENT)
class VerificationModeLenientJUnit4Test {

    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest { calculator.isPositive(5) }
        assertTrue(result, "isPositive(5) should be true")
    }
}
