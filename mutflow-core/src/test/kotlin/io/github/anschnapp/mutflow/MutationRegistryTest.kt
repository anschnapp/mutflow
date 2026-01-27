package io.github.anschnapp.mutflow

import kotlin.test.*

class MutationRegistryTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
    }

    @Test
    fun `check without session returns null`() {
        val result = MutationRegistry.check("point1", 3)
        assertNull(result)
    }

    @Test
    fun `baseline session discovers points and returns null`() {
        MutationRegistry.startSession()

        val result1 = MutationRegistry.check("point-a", 3)
        val result2 = MutationRegistry.check("point-b", 2)

        assertNull(result1, "Baseline should return null for first point")
        assertNull(result2, "Baseline should return null for second point")

        val sessionResult = MutationRegistry.endSession()
        assertEquals(2, sessionResult.mutationPointCount)
        assertEquals(
            listOf(
                DiscoveredPoint("point-a", 3),
                DiscoveredPoint("point-b", 2)
            ),
            sessionResult.discoveredPoints
        )
    }

    @Test
    fun `mutation session activates correct point by pointId`() {
        MutationRegistry.startSession(ActiveMutation(pointId = "point-b", variantIndex = 0))

        val result1 = MutationRegistry.check("point-a", 3)
        val result2 = MutationRegistry.check("point-b", 2)

        assertNull(result1, "Non-active point should return null")
        assertEquals(0, result2, "Active point should return variant index")
    }

    @Test
    fun `mutation session activates correct variant`() {
        MutationRegistry.startSession(ActiveMutation(pointId = "point-a", variantIndex = 2))

        val result = MutationRegistry.check("point-a", 3)

        assertEquals(2, result, "Should return the specified variant index")
    }

    @Test
    fun `session tracks point discovery order`() {
        MutationRegistry.startSession()

        MutationRegistry.check("z-last-alphabetically", 1)
        MutationRegistry.check("a-first-alphabetically", 2)

        val result = MutationRegistry.endSession()

        assertEquals("z-last-alphabetically", result.discoveredPoints[0].pointId)
        assertEquals("a-first-alphabetically", result.discoveredPoints[1].pointId)
    }

    @Test
    fun `cannot start session when one is active`() {
        MutationRegistry.startSession()

        assertFailsWith<IllegalStateException> {
            MutationRegistry.startSession()
        }
    }

    @Test
    fun `cannot end session when none is active`() {
        assertFailsWith<IllegalStateException> {
            MutationRegistry.endSession()
        }
    }

    @Test
    fun `hasActiveSession returns correct state`() {
        assertFalse(MutationRegistry.hasActiveSession())

        MutationRegistry.startSession()
        assertTrue(MutationRegistry.hasActiveSession())

        MutationRegistry.endSession()
        assertFalse(MutationRegistry.hasActiveSession())
    }

    @Test
    fun `can start new session after ending previous`() {
        MutationRegistry.startSession()
        MutationRegistry.check("point-a", 2)
        MutationRegistry.endSession()

        MutationRegistry.startSession()
        MutationRegistry.check("point-b", 3)
        val result = MutationRegistry.endSession()

        assertEquals(1, result.mutationPointCount)
        assertEquals("point-b", result.discoveredPoints[0].pointId)
    }

    @Test
    fun `same point checked multiple times is only discovered once`() {
        MutationRegistry.startSession()

        MutationRegistry.check("point-a", 3)
        MutationRegistry.check("point-a", 3)
        MutationRegistry.check("point-a", 3)

        val result = MutationRegistry.endSession()

        assertEquals(1, result.mutationPointCount)
        assertEquals("point-a", result.discoveredPoints[0].pointId)
    }
}
