package sample

import io.github.anschnapp.mutflow.ActiveMutation
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
    fun `discovery run finds mutation points and returns original behavior`() {
        val discovery = MutFlow.underTest(testId = "testIsPositive") {
            calculator.isPositive(5)
        }

        // Should return original result
        assertTrue(discovery.result)
        assertNull(discovery.activeMutation)

        // Should have discovered the mutation point
        assertEquals(1, discovery.discovered.size, "Should discover 1 mutation point (the > operator)")
        assertEquals(3, discovery.discovered[0].variantCount, "Should have 3 variants (>=, <, ==)")
    }

    @Test
    fun `mutation point 0 variant 0 - greater-or-equal`() {
        // Activate variant 0 (> becomes >=)
        val result = MutFlow.underTest(
            testId = "testIsPositive",
            activeMutation = ActiveMutation(pointIndex = 0, variantIndex = 0)
        ) {
            calculator.isPositive(0) // With >= mutation: 0 >= 0 is true
        }

        assertTrue(result.result, "0 >= 0 should be true (mutation effect!)")
        assertEquals(0, result.activeMutation?.pointIndex)
        assertEquals(0, result.activeMutation?.variantIndex)
    }

    @Test
    fun `mutation point 0 variant 1 - less-than`() {
        // Activate variant 1 (> becomes <)
        val result = MutFlow.underTest(
            testId = "testIsPositive",
            activeMutation = ActiveMutation(pointIndex = 0, variantIndex = 1)
        ) {
            calculator.isPositive(-5) // With < mutation: -5 < 0 is true
        }

        assertTrue(result.result, "-5 < 0 should be true (mutation effect!)")
        assertEquals(0, result.activeMutation?.pointIndex)
        assertEquals(1, result.activeMutation?.variantIndex)
    }

    @Test
    fun `mutation point 0 variant 2 - equals`() {
        // Activate variant 2 (> becomes ==)
        val result = MutFlow.underTest(
            testId = "testIsPositive",
            activeMutation = ActiveMutation(pointIndex = 0, variantIndex = 2)
        ) {
            calculator.isPositive(0) // With == mutation: 0 == 0 is true
        }

        assertTrue(result.result, "0 == 0 should be true (mutation effect!)")
        assertEquals(0, result.activeMutation?.pointIndex)
        assertEquals(2, result.activeMutation?.variantIndex)
    }

    @Test
    fun `full mutation testing loop demonstrates workflow`() {
        // This test demonstrates the full workflow that JUnit extension will automate

        // Discovery run: find mutations
        val discovery = MutFlow.underTest(testId = "testIsPositive") {
            calculator.isPositive(5)
        }
        assertTrue(discovery.result, "Baseline should return true for isPositive(5)")

        val totalVariants = discovery.discovered.sumOf { it.variantCount }
        assertEquals(3, totalVariants, "Should have 3 variants to test")

        // Test each mutation variant
        val results = mutableListOf<Boolean>()
        for (point in discovery.discovered.indices) {
            for (variant in 0 until discovery.discovered[point].variantCount) {
                MutationRegistry.reset() // Reset between runs

                val run = MutFlow.underTest(
                    testId = "testIsPositive",
                    activeMutation = ActiveMutation(point, variant)
                ) {
                    calculator.isPositive(5)
                }
                results.add(run.result)
            }
        }

        // With good tests, mutations should change behavior:
        // Variant 0 (>=): isPositive(5) still true (5 >= 0)
        // Variant 1 (<):  isPositive(5) now false (5 < 0 is false)
        // Variant 2 (==): isPositive(5) now false (5 == 0 is false)
        assertEquals(listOf(true, false, false), results)
    }
}
