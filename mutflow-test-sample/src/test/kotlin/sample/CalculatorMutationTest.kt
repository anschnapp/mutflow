package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.*

class CalculatorMutationTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
    }

    @Test
    fun `isPositive returns true for positive numbers without mutation`() {
        // No session active - should use original code
        assertTrue(calculator.isPositive(5))
        assertFalse(calculator.isPositive(-5))
        assertFalse(calculator.isPositive(0))
    }

    @Test
    fun `run 0 baseline discovers mutation points and returns original behavior`() {
        val run0 = MutFlow.underTest(runNumber = 0, testId = "testIsPositive") {
            calculator.isPositive(5)
        }

        // Should return original result
        assertTrue(run0.result)
        assertNull(run0.activeMutation)

        // Should have discovered the mutation point
        assertEquals(1, run0.discovered.size, "Should discover 1 mutation point (the > operator)")
        assertEquals(3, run0.discovered[0].variantCount, "Should have 3 variants (>=, <, ==)")
    }

    @Test
    fun `run 1 activates first mutation variant - greater-or-equal`() {
        // First: run 0 to discover mutations
        val run0 = MutFlow.underTest(runNumber = 0, testId = "testIsPositive") {
            calculator.isPositive(5)
        }

        // Run 1: activate variant 0 (> becomes >=)
        val run1 = MutFlow.underTest(
            runNumber = 1,
            testId = "testIsPositive",
            discovered = run0.discovered
        ) {
            calculator.isPositive(0) // With >= mutation: 0 >= 0 is true
        }

        assertTrue(run1.result, "0 >= 0 should be true (mutation effect!)")
        assertEquals(0, run1.activeMutation?.pointIndex)
        assertEquals(0, run1.activeMutation?.variantIndex)
    }

    @Test
    fun `run 2 activates second mutation variant - less-than`() {
        // First: run 0 to discover mutations
        val run0 = MutFlow.underTest(runNumber = 0, testId = "testIsPositive") {
            calculator.isPositive(5)
        }

        // Run 2: activate variant 1 (> becomes <)
        val run2 = MutFlow.underTest(
            runNumber = 2,
            testId = "testIsPositive",
            discovered = run0.discovered
        ) {
            calculator.isPositive(-5) // With < mutation: -5 < 0 is true
        }

        assertTrue(run2.result, "-5 < 0 should be true (mutation effect!)")
        assertEquals(0, run2.activeMutation?.pointIndex)
        assertEquals(1, run2.activeMutation?.variantIndex)
    }

    @Test
    fun `run 3 activates third mutation variant - equals`() {
        // First: run 0 to discover mutations
        val run0 = MutFlow.underTest(runNumber = 0, testId = "testIsPositive") {
            calculator.isPositive(5)
        }

        // Run 3: activate variant 2 (> becomes ==)
        val run3 = MutFlow.underTest(
            runNumber = 3,
            testId = "testIsPositive",
            discovered = run0.discovered
        ) {
            calculator.isPositive(0) // With == mutation: 0 == 0 is true
        }

        assertTrue(run3.result, "0 == 0 should be true (mutation effect!)")
        assertEquals(0, run3.activeMutation?.pointIndex)
        assertEquals(2, run3.activeMutation?.variantIndex)
    }

    @Test
    fun `totalMutations returns correct count from discovery`() {
        val run0 = MutFlow.underTest(runNumber = 0, testId = "testIsPositive") {
            calculator.isPositive(5)
        }

        assertEquals(3, MutFlow.totalMutations(run0.discovered))
    }

    @Test
    fun `full mutation testing loop demonstrates workflow`() {
        // This test demonstrates the full workflow that JUnit extension will automate

        // Run 0: Baseline - discover mutations
        val run0 = MutFlow.underTest(runNumber = 0, testId = "testIsPositive") {
            calculator.isPositive(5)
        }
        assertTrue(run0.result, "Baseline should return true for isPositive(5)")

        val totalRuns = MutFlow.totalMutations(run0.discovered)
        assertEquals(3, totalRuns, "Should have 3 mutation runs to execute")

        // Runs 1-N: Execute each mutation
        val results = mutableListOf<Boolean>()
        for (runNumber in 1..totalRuns) {
            MutationRegistry.reset() // Reset between runs (JUnit would do this via test lifecycle)

            val runN = MutFlow.underTest(
                runNumber = runNumber,
                testId = "testIsPositive",
                discovered = run0.discovered
            ) {
                calculator.isPositive(5)
            }
            results.add(runN.result)
        }

        // With good tests, mutations should change behavior:
        // Run 1 (>=): isPositive(5) still true (5 >= 0)
        // Run 2 (<):  isPositive(5) now false (5 < 0 is false)
        // Run 3 (==): isPositive(5) now false (5 == 0 is false)
        assertEquals(listOf(true, false, false), results)
    }
}
