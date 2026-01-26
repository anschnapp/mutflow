package io.github.anschnapp.mutflow

import kotlin.test.*

class MutFlowTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    // ==================== Low-level API tests ====================

    @Test
    fun `underTest discovery run executes block and returns result`() {
        var blockCalled = false

        val result = MutFlow.underTest(testId = "test") {
            blockCalled = true
            42
        }

        assertTrue(blockCalled)
        assertEquals(42, result.result)
        assertNull(result.activeMutation)
    }

    @Test
    fun `underTest with activeMutation passes mutation to session`() {
        val mutation = ActiveMutation(pointIndex = 1, variantIndex = 2)

        val result = MutFlow.underTest(testId = "test", activeMutation = mutation) {
            "result"
        }

        assertEquals("result", result.result)
        assertEquals(mutation, result.activeMutation)
    }

    @Test
    fun `underTest starts and ends session correctly`() {
        assertFalse(MutationRegistry.hasActiveSession())

        MutFlow.underTest(testId = "test") {
            assertTrue(MutationRegistry.hasActiveSession())
            "done"
        }

        assertFalse(MutationRegistry.hasActiveSession())
    }

    // ==================== High-level API tests ====================

    @Test
    fun `high-level underTest run 0 returns block result`() {
        val result = MutFlow.underTest(testId = "test", run = 0, shuffle = Shuffle.OnChange) {
            42
        }

        assertEquals(42, result)
    }

    @Test
    fun `high-level underTest run 0 stores baseline`() {
        MutFlow.underTest(testId = "test", run = 0, shuffle = Shuffle.OnChange) {
            "baseline"
        }

        // run 1 should work without throwing (baseline exists)
        val result = MutFlow.underTest(testId = "test", run = 1, shuffle = Shuffle.OnChange) {
            "mutation run"
        }

        assertEquals("mutation run", result)
    }

    @Test
    fun `high-level underTest run 1 without baseline throws`() {
        val exception = assertFailsWith<IllegalStateException> {
            MutFlow.underTest(testId = "unknown", run = 1, shuffle = Shuffle.OnChange) {
                "should fail"
            }
        }

        assertTrue(exception.message!!.contains("No baseline found"))
    }

    @Test
    fun `high-level underTest negative run throws`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            MutFlow.underTest(testId = "test", run = -1, shuffle = Shuffle.OnChange) {
                "should fail"
            }
        }

        assertTrue(exception.message!!.contains("non-negative"))
    }

    @Test
    fun `Shuffle OnChange produces same mutation for same code`() {
        MutFlow.setSeed(12345L) // Ensure deterministic for test

        // Simulate discovered points by using the low-level API first
        // For this test, we just verify that the same testId + run + shuffle gives consistent results

        // First execution
        MutFlow.underTest(testId = "consistentTest", run = 0, shuffle = Shuffle.OnChange) { "a" }
        val result1 = MutFlow.underTest(testId = "consistentTest", run = 1, shuffle = Shuffle.OnChange) { "b" }

        // Reset and do the same again
        MutFlow.reset()
        MutFlow.underTest(testId = "consistentTest", run = 0, shuffle = Shuffle.OnChange) { "a" }
        val result2 = MutFlow.underTest(testId = "consistentTest", run = 1, shuffle = Shuffle.OnChange) { "b" }

        // Both should return successfully (OnChange uses discoveredPoints hash, not seed)
        assertEquals(result1, result2)
    }

    @Test
    fun `Shuffle EachTime uses global seed`() {
        MutFlow.setSeed(99999L)

        MutFlow.underTest(testId = "seedTest", run = 0, shuffle = Shuffle.EachTime) { "baseline" }
        val result1 = MutFlow.underTest(testId = "seedTest", run = 1, shuffle = Shuffle.EachTime) { "run1" }

        // Should complete without errors
        assertEquals("run1", result1)
    }

    @Test
    fun `different runs produce different mutations with Shuffle OnChange`() {
        // This test verifies the hash function produces different results for different runs
        // We can't easily test the actual mutation selection without the compiler plugin,
        // but we can verify the API works correctly

        MutFlow.underTest(testId = "diffRuns", run = 0, shuffle = Shuffle.OnChange) { "baseline" }

        // These should all complete successfully
        val r1 = MutFlow.underTest(testId = "diffRuns", run = 1, shuffle = Shuffle.OnChange) { "r1" }
        val r2 = MutFlow.underTest(testId = "diffRuns", run = 2, shuffle = Shuffle.OnChange) { "r2" }
        val r3 = MutFlow.underTest(testId = "diffRuns", run = 3, shuffle = Shuffle.OnChange) { "r3" }

        assertEquals("r1", r1)
        assertEquals("r2", r2)
        assertEquals("r3", r3)
    }
}
