package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.*

class CalculatorMutationTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    // ==================== High-level API (recommended) ====================

    @Test
    fun `high-level API - simple mutation testing workflow`() {
        // This is the recommended way to use mutflow
        // run=0 is baseline, run=1+ are mutation runs

        // Baseline run - discovers mutation points, returns original behavior
        val baseline = MutFlow.underTest(testId = "isPositive", run = 0, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }
        assertTrue(baseline, "Baseline: isPositive(5) should be true")

        // Mutation run 1 - tests a pseudo-randomly selected mutation
        val run1 = MutFlow.underTest(testId = "isPositive", run = 1, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }
        // Result depends on which mutation was selected

        // Mutation run 2 - tests a different mutation
        val run2 = MutFlow.underTest(testId = "isPositive", run = 2, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }

        // With Shuffle.OnChange, same code = same mutations selected each time
        println("Baseline: $baseline, Run1: $run1, Run2: $run2")
    }

    @Test
    fun `high-level API - Shuffle EachTime for exploratory testing`() {
        // Use Shuffle.EachTime during development to test different mutations each build
        // Seed is auto-generated and printed to stdout for reproducibility

        val baseline = MutFlow.underTest(testId = "exploratory", run = 0, shuffle = Shuffle.EachTime) {
            calculator.isPositive(5)
        }
        assertTrue(baseline)

        // Each CI build (with different seed) tests different mutations
        val run1 = MutFlow.underTest(testId = "exploratory", run = 1, shuffle = Shuffle.EachTime) {
            calculator.isPositive(5)
        }

        val run2 = MutFlow.underTest(testId = "exploratory", run = 2, shuffle = Shuffle.EachTime) {
            calculator.isPositive(5)
        }

        println("EachTime - Run1: $run1, Run2: $run2")
    }

    @Test
    fun `high-level API - Shuffle OnChange for stable CI pipelines`() {
        // Use Shuffle.OnChange for merge request pipelines
        // Same code = same mutations = reproducible results

        val baseline = MutFlow.underTest(testId = "stable", run = 0, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }
        assertTrue(baseline)

        // These will always select the same mutations until code changes
        val run1a = MutFlow.underTest(testId = "stable", run = 1, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }

        // Reset and run again - should get same result
        MutFlow.reset()
        MutationRegistry.reset()

        MutFlow.underTest(testId = "stable", run = 0, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }
        val run1b = MutFlow.underTest(testId = "stable", run = 1, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }

        assertEquals(run1a, run1b, "OnChange should produce same mutation for same code")
    }

    @Test
    fun `high-level API - multiple runs cover more mutations over time`() {
        MutFlow.underTest(testId = "coverage", run = 0, shuffle = Shuffle.OnChange) {
            calculator.isPositive(5)
        }

        // Run multiple mutation tests - over time, we cover all mutations
        val results = (1..5).map { run ->
            MutFlow.underTest(testId = "coverage", run = run, shuffle = Shuffle.OnChange) {
                calculator.isPositive(5)
            }
        }

        println("Results across 5 runs: $results")
        // With 3 variants, some runs will test the same mutation
        // But across many CI builds, we'll cover them all
    }

    // ==================== Low-level API (plumbing) ====================

    @Test
    fun `low-level API - isPositive returns true for positive numbers without mutation`() {
        // No session active - should use original code
        assertTrue(calculator.isPositive(5))
        assertFalse(calculator.isPositive(-5))
        assertFalse(calculator.isPositive(0))
    }

    @Test
    fun `low-level API - discovery run finds mutation points`() {
        val discovery = MutFlow.underTest(testId = "testIsPositive") {
            calculator.isPositive(5)
        }

        assertTrue(discovery.result)
        assertNull(discovery.activeMutation)
        assertEquals(1, discovery.discovered.size, "Should discover 1 mutation point (the > operator)")
        assertEquals(3, discovery.discovered[0].variantCount, "Should have 3 variants (>=, <, ==)")
    }

    @Test
    fun `low-level API - explicit mutation activation`() {
        // Activate variant 0 (> becomes >=)
        val result = MutFlow.underTest(
            testId = "testIsPositive",
            activeMutation = ActiveMutation(pointIndex = 0, variantIndex = 0)
        ) {
            calculator.isPositive(0) // With >= mutation: 0 >= 0 is true
        }

        assertTrue(result.result, "0 >= 0 should be true (mutation effect!)")
    }

    @Test
    fun `low-level API - full manual mutation testing loop`() {
        // Discovery run
        val discovery = MutFlow.underTest(testId = "testIsPositive") {
            calculator.isPositive(5)
        }
        assertTrue(discovery.result)

        // Test each mutation variant manually
        val results = mutableListOf<Boolean>()
        for (point in discovery.discovered.indices) {
            for (variant in 0 until discovery.discovered[point].variantCount) {
                MutationRegistry.reset()

                val run = MutFlow.underTest(
                    testId = "testIsPositive",
                    activeMutation = ActiveMutation(point, variant)
                ) {
                    calculator.isPositive(5)
                }
                results.add(run.result)
            }
        }

        // Variant 0 (>=): 5 >= 0 = true
        // Variant 1 (<):  5 < 0 = false
        // Variant 2 (==): 5 == 0 = false
        assertEquals(listOf(true, false, false), results)
    }
}
