package io.github.anschnapp.mutflow

import kotlin.test.*

class MutFlowTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    // Helper to simulate a mutation point with default test metadata
    private fun simulateMutationPoint(pointId: String, variantCount: Int): Int? =
        MutationRegistry.check(pointId, variantCount, "Test.kt:1", ">", ">=,<,==")

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
            simulateMutationPoint("point-a", 3)
            simulateMutationPoint("point-b", 2)
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
            simulateMutationPoint("point-a", 3)
            simulateMutationPoint("point-b", 2)
            "test1"
        }

        // Second test touches point-a only
        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            simulateMutationPoint("point-a", 3)
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
            simulateMutationPoint("point-a", 2)
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
            simulateMutationPoint("point-a", 2)
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
            simulateMutationPoint("point-a", 3)
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
            simulateMutationPoint("point-a", 2)
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
            simulateMutationPoint("point-a", 2)
            simulateMutationPoint("point-b", 2)
            "test1"
        }
        MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            simulateMutationPoint("point-a", 2)
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
            simulateMutationPoint("point-b", 2)
            simulateMutationPoint("point-a", 2)
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
            simulateMutationPoint("point-a", 3)
            "baseline"
        }
        MutFlow.underTest(run = 1, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) { "r1" }
        val state1 = MutFlow.getRegistryState()
        val mutation1 = state1.testedMutations.first()

        // Reset and do the same again
        MutFlow.reset()
        MutationRegistry.reset()

        MutFlow.underTest(run = 0, selection = Selection.PureRandom, shuffle = Shuffle.PerChange) {
            simulateMutationPoint("point-a", 3)
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
            simulateMutationPoint("point-a", 3)
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

    // ==================== Target filter tests (session-based) ====================

    private fun runBaselineWithSession(sessionId: String, block: () -> Unit) {
        MutFlow.startRun(sessionId, 0)
        MutFlow.underTest(block)
        MutFlow.endRun(sessionId)
    }

    private fun runMutationWithSession(sessionId: String, run: Int): Mutation? {
        val mutation = MutFlow.selectMutationForRun(sessionId, run) ?: return null
        MutFlow.startRun(sessionId, run, mutation)
        MutFlow.underTest { /* execute with mutation active */ }
        MutFlow.endRun(sessionId)
        return mutation
    }

    @Test
    fun `includeTargets filters mutations to only included classes`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            includeTargets = listOf("com.example.Calculator")
        )

        // Baseline discovers points from two classes
        runBaselineWithSession(sessionId) {
            simulateMutationPoint("com.example.Calculator_0", 2)
            simulateMutationPoint("com.example.Logger_0", 2)
        }

        // All selected mutations should be from Calculator only
        val selected = mutableListOf<Mutation>()
        for (run in 1..5) {
            val mutation = runMutationWithSession(sessionId, run) ?: break
            selected.add(mutation)
        }

        assertTrue(selected.isNotEmpty(), "Should select at least one mutation")
        assertTrue(selected.all { it.pointId.startsWith("com.example.Calculator") },
            "All mutations should be from Calculator, got: $selected")
        assertEquals(2, selected.size, "Should exhaust only Calculator's 2 variants")

        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `excludeTargets filters out excluded classes`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            excludeTargets = listOf("com.example.Logger")
        )

        runBaselineWithSession(sessionId) {
            simulateMutationPoint("com.example.Calculator_0", 2)
            simulateMutationPoint("com.example.Logger_0", 2)
        }

        val selected = mutableListOf<Mutation>()
        for (run in 1..5) {
            val mutation = runMutationWithSession(sessionId, run) ?: break
            selected.add(mutation)
        }

        assertTrue(selected.isNotEmpty(), "Should select at least one mutation")
        assertTrue(selected.none { it.pointId.startsWith("com.example.Logger") },
            "No mutations should be from Logger, got: $selected")
        assertEquals(2, selected.size, "Should exhaust only Calculator's 2 variants")

        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `includeTargets and excludeTargets work together`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            includeTargets = listOf("com.example.Calculator", "com.example.Logger"),
            excludeTargets = listOf("com.example.Logger")
        )

        runBaselineWithSession(sessionId) {
            simulateMutationPoint("com.example.Calculator_0", 2)
            simulateMutationPoint("com.example.Logger_0", 2)
            simulateMutationPoint("com.example.Service_0", 1)
        }

        // Include narrows to Calculator+Logger, exclude removes Logger => only Calculator
        val selected = mutableListOf<Mutation>()
        for (run in 1..5) {
            val mutation = runMutationWithSession(sessionId, run) ?: break
            selected.add(mutation)
        }

        assertEquals(2, selected.size, "Should exhaust only Calculator's 2 variants")
        assertTrue(selected.all { it.pointId.startsWith("com.example.Calculator") },
            "All mutations should be from Calculator, got: $selected")

        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `empty filters preserve default behavior`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            includeTargets = emptyList(),
            excludeTargets = emptyList()
        )

        runBaselineWithSession(sessionId) {
            simulateMutationPoint("com.example.Calculator_0", 1)
            simulateMutationPoint("com.example.Logger_0", 1)
        }

        val selected = mutableListOf<Mutation>()
        for (run in 1..5) {
            val mutation = runMutationWithSession(sessionId, run) ?: break
            selected.add(mutation)
        }

        assertEquals(2, selected.size, "Both classes should contribute mutations")

        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `summary reflects filtered totals`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            includeTargets = listOf("com.example.Calculator")
        )

        runBaselineWithSession(sessionId) {
            simulateMutationPoint("com.example.Calculator_0", 2)
            simulateMutationPoint("com.example.Logger_0", 3)
        }

        val session = MutFlow.getSession(sessionId)!!
        val summary = session.getSummary()

        // Total should only count Calculator's 2 variants, not Logger's 3
        assertEquals(2, summary.totalMutations, "Total should reflect filtered set")
        assertEquals(2, summary.untested, "Untested should reflect filtered set")

        MutFlow.closeSession(sessionId)
    }

    @Test
    fun `exhaustion works correctly with target filter`() {
        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = 10,
            includeTargets = listOf("com.example.Calculator")
        )

        runBaselineWithSession(sessionId) {
            simulateMutationPoint("com.example.Calculator_0", 1)
            simulateMutationPoint("com.example.Logger_0", 5)
        }

        // Should exhaust after 1 mutation (Calculator only has 1 variant)
        val m1 = runMutationWithSession(sessionId, 1)
        assertNotNull(m1, "First mutation should be selected")

        val m2 = MutFlow.selectMutationForRun(sessionId, 2)
        assertNull(m2, "Should be exhausted after testing all filtered mutations")

        assertFalse(MutFlow.hasUntestedMutations(sessionId),
            "hasUntestedMutations should be false when filtered mutations are exhausted")

        MutFlow.closeSession(sessionId)
    }
}
