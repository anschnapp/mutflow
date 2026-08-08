package io.github.anschnapp.mutflow

import kotlin.test.*

class MutationRegistryTest {

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
    }

    // Helper to call check with default test metadata
    private fun check(pointId: String, variantCount: Int) =
        MutationRegistry.check(pointId, variantCount, "Test.kt:1", ">", ">=,<,==")

    @Test
    fun `check without session returns null`() {
        val result = check("point1", 3)
        assertNull(result)
    }

    @Test
    fun `baseline session discovers points and returns null`() {
        MutationRegistry.startSession()

        val result1 = check("point-a", 3)
        val result2 = check("point-b", 2)

        assertNull(result1, "Baseline should return null for first point")
        assertNull(result2, "Baseline should return null for second point")

        val sessionResult = MutationRegistry.endSession()
        assertEquals(2, sessionResult.mutationPointCount)
        assertEquals("point-a", sessionResult.discoveredPoints[0].pointId)
        assertEquals(3, sessionResult.discoveredPoints[0].variantCount)
        assertEquals("point-b", sessionResult.discoveredPoints[1].pointId)
        assertEquals(2, sessionResult.discoveredPoints[1].variantCount)
    }

    @Test
    fun `mutation session activates correct point by pointId`() {
        MutationRegistry.startSession(ActiveMutation(pointId = "point-b", variantIndex = 0))

        val result1 = check("point-a", 3)
        val result2 = check("point-b", 2)

        assertNull(result1, "Non-active point should return null")
        assertEquals(0, result2, "Active point should return variant index")
    }

    @Test
    fun `mutation session activates correct variant`() {
        MutationRegistry.startSession(ActiveMutation(pointId = "point-a", variantIndex = 2))

        val result = check("point-a", 3)

        assertEquals(2, result, "Should return the specified variant index")
    }

    @Test
    fun `session tracks point discovery order`() {
        MutationRegistry.startSession()

        check("z-last-alphabetically", 1)
        check("a-first-alphabetically", 2)

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
        check("point-a", 2)
        MutationRegistry.endSession()

        MutationRegistry.startSession()
        check("point-b", 3)
        val result = MutationRegistry.endSession()

        assertEquals(1, result.mutationPointCount)
        assertEquals("point-b", result.discoveredPoints[0].pointId)
    }

    @Test
    fun `same point checked multiple times is only discovered once`() {
        MutationRegistry.startSession()

        check("point-a", 3)
        check("point-a", 3)
        check("point-a", 3)

        val result = MutationRegistry.endSession()

        assertEquals(1, result.mutationPointCount)
        assertEquals("point-a", result.discoveredPoints[0].pointId)
    }

    @Test
    fun `discovered point includes metadata`() {
        MutationRegistry.startSession()

        MutationRegistry.check("point-a", 3, "Calculator.kt:5", ">", ">=,<,==")

        val result = MutationRegistry.endSession()

        assertEquals(1, result.mutationPointCount)
        val point = result.discoveredPoints[0]
        assertEquals("point-a", point.pointId)
        assertEquals(3, point.variantCount)
        assertEquals("Calculator.kt:5", point.sourceLocation)
        assertEquals(">", point.originalOperator)
        assertEquals(listOf(">=", "<", "=="), point.variantOperators)
    }
}
