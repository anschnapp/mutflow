package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.VerificationMode
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * ACCUMULATE mode: like LENIENT, surviving mutations do not fail this class. Its verdicts go
 * to `build/mutflow/results/sample.VerificationModeAccumulateTest.json` (the working directory
 * is the module directory under Gradle) for the merged report instead. Same deliberately weak
 * coverage as [VerificationModeLenientTest], so the file records survivors.
 */
@MutFlowTest(verificationMode = VerificationMode.ACCUMULATE)
class VerificationModeAccumulateTest {

    private val calculator = Calculator()

    @Test
    fun `isPositive returns true for positive numbers`() {
        val result = MutFlow.underTest {
            calculator.isPositive(5)
        }
        assertTrue(result, "isPositive(5) should be true")
    }
}
