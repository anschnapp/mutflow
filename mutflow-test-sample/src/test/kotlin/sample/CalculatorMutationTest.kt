package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.MutationsExhaustedException
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.*

class CalculatorMutationTest {

    private val calculator = Calculator()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    // ==================== Basic workflow ====================

    @Test
    fun `basic mutation testing workflow`() {
        // run=0 is baseline for ALL tests, discovers mutation points
        // run=1+ are mutation runs where ALL tests run with the SAME mutation active

        // Baseline run - discovers mutation points, returns original behavior
        val baseline = MutFlow.underTest(
            run = 0,
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange
        ) {
            calculator.isPositive(5)
        }
        assertTrue(baseline, "Baseline: isPositive(5) should be true")

        // Mutation run 1 - tests a selected mutation
        val run1 = MutFlow.underTest(
            run = 1,
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange
        ) {
            calculator.isPositive(5)
        }
        // Result depends on which mutation was selected

        // Mutation run 2 - tests a different mutation
        val run2 = MutFlow.underTest(
            run = 2,
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange
        ) {
            calculator.isPositive(5)
        }

        println("Baseline: $baseline, Run1: $run1, Run2: $run2")
    }

    // ==================== Selection strategies ====================

    @Test
    fun `Selection PureRandom for uniform random selection`() {
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerRun) {
            calculator.isPositive(5)
        }

        // PureRandom selects uniformly among untested mutations
        val run1 = MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerRun) {
            calculator.isPositive(5)
        }

        println("PureRandom - Run1: $run1")
    }

    @Test
    fun `Selection MostLikelyRandom favors under-tested mutations`() {
        // Setup: two tests touch point differently
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)  // Touches the mutation point
        }

        // MostLikelyRandom weights selection toward mutations with lower touch counts
        val run1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        println("MostLikelyRandom - Run1: $run1")
    }

    @Test
    fun `Selection MostLikelyStable is deterministic`() {
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        // MostLikelyStable always picks the mutation with lowest touch count
        // Tie-breaker: alphabetical by pointId, then by variantIndex
        val run1a = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        // Reset and run again - should get same result
        MutFlow.reset()
        MutationRegistry.reset()

        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        val run1b = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        assertEquals(run1a, run1b, "MostLikelyStable should produce same result for same code")
    }

    // ==================== Shuffle modes ====================

    @Test
    fun `Shuffle PerRun for exploratory testing`() {
        // Use Shuffle.PerRun during development to test different mutations each build
        // Seed is auto-generated and printed to stdout for reproducibility

        val baseline = MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerRun) {
            calculator.isPositive(5)
        }
        assertTrue(baseline)

        // Each CI build (with different seed) tests different mutations
        val run1 = MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerRun) {
            calculator.isPositive(5)
        }

        val run2 = MutFlow.underTest(run = 2, selection = Selection.PureRandom, shuffle = Shuffle.PerRun) {
            calculator.isPositive(5)
        }

        println("PerRun - Run1: $run1, Run2: $run2")
    }

    @Test
    fun `Shuffle PerChange for stable CI pipelines`() {
        // Use Shuffle.PerChange for merge request pipelines
        // Same code = same mutations = reproducible results

        val baseline = MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        assertTrue(baseline)

        // These will always select the same mutations until code changes
        val run1a = MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        // Reset and run again - should get same result
        MutFlow.reset()
        MutationRegistry.reset()

        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        val run1b = MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        assertEquals(run1a, run1b, "PerChange should produce same mutation for same code")
    }

    // ==================== Mutation exhaustion ====================

    @Test
    fun `throws MutationsExhaustedException when all mutations tested`() {
        // The calculator.isPositive has 2 mutation points:
        // - Operator mutation (> → >=, > → <): 2 variants
        // - Constant boundary mutation (0 → 1, 0 → -1): 2 variants
        // Total: 4 mutations

        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        // Test all 4 mutations
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        MutFlow.underTest(run = 2, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        MutFlow.underTest(run = 3, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        MutFlow.underTest(run = 4, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        // Fifth run should throw - all mutations exhausted
        assertFailsWith<MutationsExhaustedException> {
            MutFlow.underTest(run = 5, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
                calculator.isPositive(5)
            }
        }
    }

    // ==================== Multiple tests in same run ====================

    @Test
    fun `multiple tests share same mutation in a run`() {
        // In real usage, JUnit extension would orchestrate this
        // Note: With the legacy explicit API, each underTest call selects a NEW mutation
        // This test verifies that mutation selection works across multiple calls

        // Baseline for multiple tests
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(-5)
        }

        // With 4 mutations total, we can do 4 mutation runs
        val r1 = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        val r2 = MutFlow.underTest(run = 2, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        println("Run results: r1=$r1, r2=$r2")
    }

    // ==================== Original behavior without mutation ====================

    @Test
    fun `isPositive returns correct results without mutation`() {
        // No session active - should use original code
        assertTrue(calculator.isPositive(5))
        assertFalse(calculator.isPositive(-5))
        assertFalse(calculator.isPositive(0))
    }

    @Test
    fun `baseline run returns original behavior`() {
        val positive = MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        val negative = MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(-5)
        }
        val zero = MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(0)
        }

        assertTrue(positive, "isPositive(5) should be true")
        assertFalse(negative, "isPositive(-5) should be false")
        assertFalse(zero, "isPositive(0) should be false")
    }

    // ==================== Return value mutations ====================

    @Test
    fun `isInRange has return value mutations`() {
        // isInRange uses explicit return (block body), so it gets return value mutations
        // In addition to comparison operator mutations

        // Baseline - discover mutations
        val baseline = MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isInRange(5, 0, 10)
        }
        assertTrue(baseline, "Baseline: isInRange(5, 0, 10) should be true")

        val state = MutFlow.getRegistryState()
        // isInRange(x, min, max) has:
        // - x >= min: 2 operator variants (>, <=)
        // - x <= max: 2 operator variants (<, >=)
        // - return: 2 variants (true, false)
        // Total: 6 mutations (3 mutation points × 2 variants each)
        val totalMutations = state.discoveredPoints.values.sum()
        assertEquals(6, totalMutations, "isInRange should have 6 total mutations")

        // Should have 3 mutation points (2 comparisons + 1 return)
        assertEquals(3, state.discoveredPoints.size, "isInRange should have 3 mutation points")
    }

    @Test
    fun `return mutation can replace result with true or false`() {
        // This test verifies that tests which don't check both outcomes would fail

        // Baseline
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isInRange(5, 0, 10)
        }

        // Track mutation results - with return mutations, some runs will return
        // constant true or false regardless of the actual logic
        var sawDifferentResult = false
        var originalResult = true

        // Run several mutation tests
        for (run in 1..6) {
            try {
                val result = MutFlow.underTest(run = run, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
                    calculator.isInRange(5, 0, 10)
                }
                if (result != originalResult) {
                    sawDifferentResult = true
                }
            } catch (e: MutationsExhaustedException) {
                // Expected when all mutations are tested
                break
            }
        }

        // Some mutation should have changed the result
        assertTrue(sawDifferentResult, "At least one mutation should change the result")
    }
}
