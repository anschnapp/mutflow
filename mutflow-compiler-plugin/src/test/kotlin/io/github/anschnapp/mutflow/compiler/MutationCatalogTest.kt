package io.github.anschnapp.mutflow.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the declarative mutation catalog: every operator is registered with a
 * stable, unique id, correct group/status, and the transformer defaults match.
 */
class MutationCatalogTest {

    @Test
    fun `all operators are registered`() {
        assertEquals(13, MutationCatalog.callOperators.size)
        assertEquals(1, MutationCatalog.experimentalCallOperators.size)
        assertEquals(5, MutationCatalog.returnOperators.size)
        assertEquals(1, MutationCatalog.functionBodyOperators.size)
        assertEquals(4, MutationCatalog.whenOperators.size)
        assertEquals(2, MutationCatalog.constOperators.size)
        assertEquals(1, MutationCatalog.constructorCallOperators.size)
        assertEquals(1, MutationCatalog.assignmentOperators.size)
        assertEquals(27, MutationCatalog.allDescriptors.size)
    }

    @Test
    fun `ids are unique and non-blank`() {
        val ids = MutationCatalog.allDescriptors.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "mutator ids must be unique")
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun `validate passes for the current catalog`() {
        MutationCatalog.validate() // should not throw
    }

    @Test
    fun `every descriptor has name and description`() {
        MutationCatalog.allDescriptors.forEach { d ->
            assertTrue(d.name.isNotBlank(), "name blank for ${d.id}")
            assertTrue(d.description.isNotBlank(), "description blank for ${d.id}")
        }
    }

    @Test
    fun `byId finds registered operators`() {
        assertNotNull(MutationCatalog.byId("RELATIONAL_COMPARISON"))
        assertNotNull(MutationCatalog.byId("ARITHMETIC_SWAP"))
        assertNotNull(MutationCatalog.byId("CONSTANT_BOUNDARY"))
        assertNotNull(MutationCatalog.byId("EQUALITY_SWAP"))
        assertNotNull(MutationCatalog.byId("BOOLEAN_INVERSION"))
        assertNotNull(MutationCatalog.byId("BOOLEAN_LOGIC"))
        assertNotNull(MutationCatalog.byId("RETURN_BOOLEAN"))
        assertNotNull(MutationCatalog.byId("RETURN_NULLABLE"))
        assertNotNull(MutationCatalog.byId("RETURN_PRIMITIVE"))
        assertNotNull(MutationCatalog.byId("RETURN_OBJECT"))
        assertNotNull(MutationCatalog.byId("UNARY_MINUS"))
        assertNotNull(MutationCatalog.byId("BITWISE_SWAP"))
        assertNotNull(MutationCatalog.byId("INCREMENT"))
        assertNotNull(MutationCatalog.byId("FORCE_CONDITIONAL"))
        assertNotNull(MutationCatalog.byId("STRING_LITERAL"))
        assertNotNull(MutationCatalog.byId("BOOLEAN_CONST"))
        assertNotNull(MutationCatalog.byId("NON_VOID_CALL"))
        assertNotNull(MutationCatalog.byId("VOID_FUNCTION_BODY"))
        assertNotNull(MutationCatalog.byId("CONSTRUCTOR_CALL"))
        assertNotNull(MutationCatalog.byId("REMOVE_INCREMENT"))
        assertNotNull(MutationCatalog.byId("STRING_METHOD"))
        assertNotNull(MutationCatalog.byId("COLLECTION_METHOD"))
        assertNotNull(MutationCatalog.byId("REFERENCE_EQUALITY_SWAP"))
        assertNotNull(MutationCatalog.byId("ELVIS"))
        assertNotNull(MutationCatalog.byId("SAFE_CALL"))
        assertNotNull(MutationCatalog.byId("RETURN_EMPTY_COLLECTION"))
        assertNotNull(MutationCatalog.byId("ASSIGN_CONST"))
    }

    @Test
    fun `byId returns null for unknown id`() {
        assertNull(MutationCatalog.byId("DOES_NOT_EXIST"))
    }

    @Test
    fun `byGroup groups operators correctly`() {
        assertEquals(3, MutationCatalog.byGroup(MutatorGroup.RELATIONAL).size) // RelationalComparison + EqualitySwap + ReferenceEqualitySwap
        assertEquals(6, MutationCatalog.byGroup(MutatorGroup.ARITHMETIC).size) // ArithmeticSwap + UnaryMinus + BitwiseSwap + Increment + RemoveIncrement + AssignConst
        assertEquals(3, MutationCatalog.byGroup(MutatorGroup.BOOLEAN).size)   // BooleanInversion + BooleanLogic + BooleanConst
        assertEquals(1, MutationCatalog.byGroup(MutatorGroup.CONSTANT).size)
        assertEquals(5, MutationCatalog.byGroup(MutatorGroup.RETURN).size)
        assertEquals(3, MutationCatalog.byGroup(MutatorGroup.CALL).size)   // ReplaceNonVoid + ConstructorCall + VoidFunctionBody
        assertEquals(1, MutationCatalog.byGroup(MutatorGroup.CONTROL_FLOW).size)
        assertEquals(2, MutationCatalog.byGroup(MutatorGroup.KOTLIN_SPECIFIC).size) // Elvis + SafeCall
        assertEquals(2, MutationCatalog.byGroup(MutatorGroup.STRING).size)   // StringLiteral + StringMethod
        assertEquals(1, MutationCatalog.byGroup(MutatorGroup.COLLECTION).size)
    }

    @Test
    fun `stable descriptors exclude experimental`() {
        assertEquals(26, MutationCatalog.stableDescriptors.size)
        assertTrue(MutationCatalog.stableDescriptors.all { it.status == MutatorStatus.STABLE })
        assertTrue(MutationCatalog.allDescriptors.any { it.status == MutatorStatus.EXPERIMENTAL })
    }

    @Test
    fun `transformer defaults match the catalog`() {
        assertEquals(
            MutationCatalog.callOperators.map { it.descriptor.id },
            MutflowIrTransformer.defaultCallOperators().map { it.descriptor.id }
        )
        assertEquals(
            MutationCatalog.returnOperators.map { it.descriptor.id },
            MutflowIrTransformer.defaultReturnOperators().map { it.descriptor.id }
        )
        assertEquals(
            MutationCatalog.functionBodyOperators.map { it.descriptor.id },
            MutflowIrTransformer.defaultFunctionBodyOperators().map { it.descriptor.id }
        )
        assertEquals(
            MutationCatalog.whenOperators.map { it.descriptor.id },
            MutflowIrTransformer.defaultWhenOperators().map { it.descriptor.id }
        )
        assertEquals(
            MutationCatalog.constOperators.map { it.descriptor.id },
            MutflowIrTransformer.defaultConstOperators().map { it.descriptor.id }
        )
        assertEquals(
            MutationCatalog.constructorCallOperators.map { it.descriptor.id },
            MutflowIrTransformer.defaultConstructorCallOperators().map { it.descriptor.id }
        )
        assertEquals(
            MutationCatalog.assignmentOperators.map { it.descriptor.id },
            MutflowIrTransformer.defaultAssignmentOperators().map { it.descriptor.id }
        )
    }

    @Test
    fun `descriptors are stable across instances`() {
        // Two instances of the same operator must report identical descriptors.
        val a = RelationalComparisonOperator().descriptor
        val b = RelationalComparisonOperator().descriptor
        assertEquals(a, b)
    }
}
