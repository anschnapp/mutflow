package io.github.anschnapp.mutflow

import kotlin.test.*

class MutFlowTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
    }

    @Test
    fun `selectMutation maps run 1 to first point first variant`() {
        val discovered = listOf(
            DiscoveredPoint("p1", 3),
            DiscoveredPoint("p2", 2)
        )

        val result = MutFlow.selectMutation(1, discovered)

        assertEquals(ActiveMutation(pointIndex = 0, variantIndex = 0), result)
    }

    @Test
    fun `selectMutation maps run 3 to first point third variant`() {
        val discovered = listOf(
            DiscoveredPoint("p1", 3),
            DiscoveredPoint("p2", 2)
        )

        val result = MutFlow.selectMutation(3, discovered)

        assertEquals(ActiveMutation(pointIndex = 0, variantIndex = 2), result)
    }

    @Test
    fun `selectMutation maps run 4 to second point first variant`() {
        val discovered = listOf(
            DiscoveredPoint("p1", 3),
            DiscoveredPoint("p2", 2)
        )

        val result = MutFlow.selectMutation(4, discovered)

        assertEquals(ActiveMutation(pointIndex = 1, variantIndex = 0), result)
    }

    @Test
    fun `selectMutation maps run 5 to second point second variant`() {
        val discovered = listOf(
            DiscoveredPoint("p1", 3),
            DiscoveredPoint("p2", 2)
        )

        val result = MutFlow.selectMutation(5, discovered)

        assertEquals(ActiveMutation(pointIndex = 1, variantIndex = 1), result)
    }

    @Test
    fun `selectMutation throws when run exceeds available mutations`() {
        val discovered = listOf(
            DiscoveredPoint("p1", 3),
            DiscoveredPoint("p2", 2)
        )

        assertFailsWith<IllegalArgumentException> {
            MutFlow.selectMutation(6, discovered)
        }
    }

    @Test
    fun `totalMutations sums all variant counts`() {
        val discovered = listOf(
            DiscoveredPoint("p1", 3),
            DiscoveredPoint("p2", 2),
            DiscoveredPoint("p3", 4)
        )

        assertEquals(9, MutFlow.totalMutations(discovered))
    }

    @Test
    fun `underTest run 0 requires no discovered mutations`() {
        var blockCalled = false

        val result = MutFlow.underTest(runNumber = 0, testId = "test") {
            blockCalled = true
            42
        }

        assertTrue(blockCalled)
        assertEquals(42, result.result)
        assertNull(result.activeMutation)
    }

    @Test
    fun `underTest run 1 requires discovered mutations`() {
        assertFailsWith<IllegalArgumentException> {
            MutFlow.underTest(runNumber = 1, testId = "test") { 42 }
        }
    }

    @Test
    fun `underTest negative run number throws`() {
        assertFailsWith<IllegalArgumentException> {
            MutFlow.underTest(runNumber = -1, testId = "test") { 42 }
        }
    }
}
