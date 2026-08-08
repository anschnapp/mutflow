package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * Replaces a collection return with the empty one: `return listOf(1,2,3)` →
 * `return emptyList()`, and similarly for `emptySet()`/`emptyMap()`.
 *
 * Pitest's `EMPTY_RETURNS`. Fires on explicit source returns whose declared
 * type is a `List`/`Set`/`Map`/`Collection`; keeps the return type's type
 * arguments (so `List<Int>` → `emptyList<Int>()`).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class EmptyCollectionReturnOperator : ReturnMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "RETURN_EMPTY_COLLECTION",
        name = "EmptyCollectionReturn",
        description = "Replace collection return with empty collection",
        group = MutatorGroup.RETURN,
        status = MutatorStatus.STABLE
    )

    private enum class CollectionKind { LIST, SET, MAP, COLLECTION }

    private val COLLECTION_KINDS = mapOf(
        "kotlin.collections.List" to CollectionKind.LIST,
        "kotlin.collections.Collection" to CollectionKind.LIST,
        "kotlin.collections.MutableList" to CollectionKind.LIST,
        "kotlin.collections.MutableCollection" to CollectionKind.LIST,
        "kotlin.collections.Set" to CollectionKind.SET,
        "kotlin.collections.MutableSet" to CollectionKind.SET,
        "kotlin.collections.Map" to CollectionKind.MAP,
        "kotlin.collections.MutableMap" to CollectionKind.MAP
    )

    private val EMPTY_FN_NAMES = mapOf(
        CollectionKind.LIST to "emptyList",
        CollectionKind.SET to "emptySet",
        CollectionKind.MAP to "emptyMap"
    )

    override fun matches(ret: IrReturn): Boolean {
        // Skip synthetic returns (expression-bodied functions).
        if (ret.startOffset == UNDEFINED_OFFSET || ret.startOffset < 0) return false
        if (ret.startOffset == ret.endOffset) return false

        val returnTarget = ret.returnTargetSymbol.owner
        val functionReturnType = when (returnTarget) {
            is IrFunction -> returnTarget.returnType
            else -> ret.value.type
        }
        // Only a collection/map return type is a valid target.
        return collectionKind(functionReturnType) != null
    }

    override fun originalDescription(ret: IrReturn): String = "return ..."

    override fun variants(ret: IrReturn, context: MutationContext): List<MutationOperator.Variant> {
        val returnTarget = ret.returnTargetSymbol.owner
        val functionReturnType = when (returnTarget) {
            is IrFunction -> returnTarget.returnType
            else -> ret.value.type
        }
        val kind = collectionKind(functionReturnType) ?: return emptyList()
        val emptyFnName = EMPTY_FN_NAMES[kind] ?: return emptyList()

        // Resolve the stdlib `emptyList`/`emptySet`/`emptyMap` function.
        val emptyFn = context.pluginContext.referenceFunctions(
            CallableId(org.jetbrains.kotlin.name.FqName("kotlin.collections"), Name.identifier(emptyFnName))
        ).firstOrNull() ?: return emptyList()

        // Preserve the type arguments of the return type (e.g. List<Int> → emptyList<Int>()).
        val typeArguments = (functionReturnType as? org.jetbrains.kotlin.ir.types.IrSimpleType)
            ?.arguments
            ?.mapNotNull { it as? org.jetbrains.kotlin.ir.types.IrTypeProjection }
            ?.map { it.type }
            .orEmpty()

        return listOf(
            MutationOperator.Variant(emptyFnName) {
                context.builder.irCall(emptyFn).also { call ->
                    typeArguments.forEachIndexed { index, arg ->
                        if (index < call.typeArguments.size) {
                            call.typeArguments[index] = arg
                        }
                    }
                }
            }
        )
    }

    private fun collectionKind(type: IrType): CollectionKind? {
        val fqName = type.classFqName?.asString() ?: return null
        return COLLECTION_KINDS[fqName]
    }
}
