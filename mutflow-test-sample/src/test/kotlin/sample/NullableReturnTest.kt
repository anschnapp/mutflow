package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.*

/**
 * Tests for nullable return mutations.
 *
 * The NullableReturnOperator mutates functions that return nullable types
 * to always return null. This catches tests that:
 * 1. Only verify non-null but don't check the actual value
 * 2. Don't properly test the null case in callers
 */
class NullableReturnTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    @Test
    fun `nullable return generates null mutation`() {
        // getPositiveOrNull returns Int? with explicit return statements
        // Should generate a "return null" mutation variant

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.getPositiveOrNull(5)
        }

        val state = MutFlow.getRegistryState()

        // Should discover mutation points:
        // - Comparison operator mutations (> → >=, > → <)
        // - Constant boundary mutations (0 → 1, 0 → -1)
        // - Nullable return mutation (return x → return null)
        val points = state.discoveredPoints.entries.filter { it.key.contains("Calculator") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points for getPositiveOrNull")

        // Print discovered mutations for debugging
        println("Discovered mutation points:")
        points.forEach { (pointId, variantCount) ->
            println("  $pointId (variants: $variantCount)")
        }

        // Check that we have a nullable return mutation (1 variant: null)
        // The null return mutation has exactly 1 variant
        val nullReturnPoint = points.find { (_, variantCount) -> variantCount == 1 }
        assertNotNull(nullReturnPoint, "Should have a mutation point with 1 variant (nullable return). " +
            "Found: ${points.map { "${it.key}=${it.value}" }}")
    }

    @Test
    fun `nullable return mutation changes behavior`() {
        // Original: getPositiveOrNull(5) returns 5
        // Mutation: getPositiveOrNull(5) returns null

        // Baseline - should return 5
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.getPositiveOrNull(5)
        }
        assertEquals(5, baseline, "getPositiveOrNull(5) should return 5")

        // Find which mutation run has the null return mutation
        // The mutations are ordered by MostLikelyStable, so we need to find the right one
        val state = MutFlow.getRegistryState()

        // Try mutation runs until we find one that returns null
        var foundNullMutation = false
        for (run in 1..10) {
            try {
                val result = MutFlow.underTest(run = run, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
                    calculator.getPositiveOrNull(5)
                }
                if (result == null) {
                    foundNullMutation = true
                    break
                }
            } catch (e: Exception) {
                // MutationsExhaustedException means we've tested all mutations
                break
            }
        }

        assertTrue(foundNullMutation, "Should have a mutation that returns null for getPositiveOrNull(5)")
    }

    @Test
    fun `good test catches null mutation`() {
        // A good test verifies the actual return value, not just non-null
        // This test should catch the null mutation

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.getPositiveOrNull(5)
        }

        // This assertion verifies the actual value - catches null mutation
        assertEquals(5, baseline, "Should return the input value for positive numbers")
    }

    @Test
    fun `weak test would miss null mutation`() {
        // This demonstrates a weak test pattern that only checks non-null
        // In a real scenario, the null mutation would survive this test

        // Baseline
        val baseline = MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.getPositiveOrNull(5)
        }

        // This weak assertion only checks non-null
        // If the mutation returned null, this would fail
        // But if it returned a different non-null value, it would pass
        assertNotNull(baseline, "Should return non-null for positive numbers")

        // Note: The null mutation WOULD be caught by assertNotNull
        // A truly weak test would be one that doesn't check the return at all:
        // calculator.getPositiveOrNull(5)  // no assertion
    }
}
