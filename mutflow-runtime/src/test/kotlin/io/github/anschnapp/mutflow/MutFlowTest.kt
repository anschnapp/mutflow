package io.github.anschnapp.mutflow

import kotlin.test.*

class MutFlowTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    // ==================== Baseline tests ====================

    @Test
    fun `baseline run returns block result`() {
        val result = MutFlow.underTest(
            run = 0,
            selection = Selection.PureRandom,
            shuffle = Shuffle.PerChange
        ) {
            42
        }

        assertEquals(42, result)
    }

    @Test
    fun `baseline run discovers mutation points and updates touch counts`() {
        // Simulate mutation points being touched during baseline
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            // In real usage, compiler-injected code would call MutationRegistry.check()
            MutationRegistry.check("point-a", 3)
            MutationRegistry.check("point-b", 2)
            "result"
        }

        val state = MutFlow.getRegistryState()
        assertEquals(mapOf("point-a" to 3, "point-b" to 2), state.discoveredPoints)
        assertEquals(mapOf("point-a" to 1, "point-b" to 1), state.touchCounts)
    }

    @Test
    fun `multiple baseline runs increment touch counts`() {
        // First test touches point-a and point-b
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 3)
            MutationRegistry.check("point-b", 2)
            "test1"
        }

        // Second test touches point-a only
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 3)
            "test2"
        }

        val state = MutFlow.getRegistryState()
        assertEquals(2, state.touchCounts["point-a"], "point-a touched by 2 tests")
        assertEquals(1, state.touchCounts["point-b"], "point-b touched by 1 test")
    }

    // ==================== Mutation run tests ====================

    @Test
    fun `mutation run returns block result`() {
        // Setup baseline
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 2)
            "baseline"
        }

        val result = MutFlow.underTest(
            run = 1,
            selection = Selection.PureRandom,
            shuffle = Shuffle.PerChange
        ) {
            "mutation run"
        }

        assertEquals("mutation run", result)
    }

    @Test
    fun `mutation run tracks tested mutations`() {
        MutFlow.setSeed(12345L)

        // Setup baseline
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 2)
            "baseline"
        }

        // Run mutation test
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            "run1"
        }

        val state = MutFlow.getRegistryState()
        assertEquals(1, state.testedMutations.size, "One mutation should be tested")
    }

    @Test
    fun `mutation runs test different mutations`() {
        MutFlow.setSeed(12345L)

        // Setup baseline with point that has 3 variants
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 3)
            "baseline"
        }

        // Run 3 mutation tests
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r1" }
        MutFlow.underTest(run = 2, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r2" }
        MutFlow.underTest(run = 3, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r3" }

        val state = MutFlow.getRegistryState()
        assertEquals(3, state.testedMutations.size, "Three different mutations should be tested")
    }

    @Test
    fun `throws MutationsExhaustedException when all mutations tested`() {
        MutFlow.setSeed(12345L)

        // Setup baseline with only 2 mutations total
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 2)
            "baseline"
        }

        // Test both mutations
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r1" }
        MutFlow.underTest(run = 2, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r2" }

        // Third run should throw
        assertFailsWith<MutationsExhaustedException> {
            MutFlow.underTest(run = 3, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r3" }
        }
    }

    @Test
    fun `negative run throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            MutFlow.underTest(
                run = -1,
                selection = Selection.PureRandom,
                shuffle = Shuffle.PerChange
            ) {
                "should fail"
            }
        }

        assertTrue(exception.message!!.contains("non-negative"))
    }

    // ==================== Selection strategy tests ====================

    @Test
    fun `MostLikelyStable selects mutation with lowest touch count`() {
        // Setup: point-a touched by 2 tests, point-b touched by 1 test
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 2)
            MutationRegistry.check("point-b", 2)
            "test1"
        }
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 2)
            "test2"
        }

        // point-b has lower touch count, should be selected first
        MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) { "r1" }

        val state = MutFlow.getRegistryState()
        val testedMutation = state.testedMutations.first()
        assertEquals("point-b", testedMutation.pointId, "Should select point with lowest touch count")
    }

    @Test
    fun `MostLikelyStable uses alphabetical tie-breaker`() {
        // Setup: both points touched equally
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-b", 2)
            MutationRegistry.check("point-a", 2)
            "test1"
        }

        // point-a comes first alphabetically
        MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) { "r1" }

        val state = MutFlow.getRegistryState()
        val testedMutation = state.testedMutations.first()
        assertEquals("point-a", testedMutation.pointId, "Should use alphabetical tie-breaker")
    }

    // ==================== Shuffle mode tests ====================

    @Test
    fun `Shuffle PerChange produces same selection for same discovered points`() {
        // First execution
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 3)
            "baseline"
        }
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r1" }
        val state1 = MutFlow.getRegistryState()
        val mutation1 = state1.testedMutations.first()

        // Reset and do the same again
        MutFlow.reset()
        MutationRegistry.reset()

        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            MutationRegistry.check("point-a", 3)
            "baseline"
        }
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r1" }
        val state2 = MutFlow.getRegistryState()
        val mutation2 = state2.testedMutations.first()

        assertEquals(mutation1, mutation2, "PerChange should produce same mutation for same code")
    }

    @Test
    fun `Shuffle PerRun uses global seed`() {
        MutFlow.setSeed(99999L)

        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerRun) {
            MutationRegistry.check("point-a", 3)
            "baseline"
        }

        val result = MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerRun) {
            "run1"
        }

        assertEquals("run1", result)
    }

    // ==================== Edge cases ====================

    @Test
    fun `mutation run with no discovered points just runs block`() {
        // No baseline, so no discovered points
        // This simulates code without @MutationTarget

        val result = MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            "no mutations"
        }

        assertEquals("no mutations", result)
    }
}
