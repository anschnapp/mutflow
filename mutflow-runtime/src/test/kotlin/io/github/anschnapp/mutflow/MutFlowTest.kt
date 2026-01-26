package io.github.anschnapp.mutflow

import kotlin.test.*

class MutFlowTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
    }

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
}
