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
        // The calculator.isPositive has 1 mutation point with 3 variants

        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        // Test all 3 mutations
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        MutFlow.underTest(run = 2, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        MutFlow.underTest(run = 3, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }

        // Fourth run should throw - all mutations exhausted
        assertFailsWith<MutationsExhaustedException> {
            MutFlow.underTest(run = 4, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
                calculator.isPositive(5)
            }
        }
    }

    // ==================== Multiple tests in same run ====================

    @Test
    fun `multiple tests share same mutation in a run`() {
        // In real usage, JUnit extension would orchestrate this
        // All tests in run=1 see the SAME mutation active

        // Baseline for multiple tests
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(-5)
        }
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(0)
        }

        // All mutation runs in run=1 will have the same mutation active
        // If ANY test fails, the mutation is killed
        val r1a = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(5)
        }
        val r1b = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(-5)
        }
        val r1c = MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            calculator.isPositive(0)
        }

        println("Run 1 results: positive=$r1a, negative=$r1b, zero=$r1c")
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
}
