package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * Mutation operator for Kotlin stdlib collection-method calls.
 *
 * Maps Stryker's Scala collection-method mutators to Kotlin stdlib equivalents
 * (see `docs/mutation-catalog.md` §3.10). All are backend-agnostic since the
 * Kotlin stdlib is shared across JVM/JS/WASM/Native.
 *
 * Swaps (name → replacement):
 * - `filter` ↔ `filterNot`
 * - `any` ↔ `all`
 * - `take` ↔ `drop`
 * - `takeLast` ↔ `dropLast`
 * - `isEmpty` ↔ `isNotEmpty`
 * - `min` ↔ `max`
 * - `minBy` ↔ `maxBy`
 * - `minOf` ↔ `maxOf`
 *
 * (`indexOf`↔`lastIndexOf` is deliberately omitted — its replacement symbol is not
 * present in the WASM function map, so it breaks Kotlin/WASM compilation.)
 *
 * Matched by receiver type (a collection/array/String) + method name, since
 * these are regular (origin == null) member calls with no dedicated
 * IrStatementOrigin.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class CollectionMethodOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "COLLECTION_METHOD",
        name = "CollectionMethod",
        description = "Swap filter↔filterNot, any↔all, take↔drop, isEmpty↔isNotEmpty, min↔max, minBy↔maxBy",
        group = MutatorGroup.COLLECTION,
        status = MutatorStatus.STABLE
    )

    private companion object {
        private val SWAPS = mapOf(
            "filter" to "filterNot",
            "filterNot" to "filter",
            "any" to "all",
            "all" to "any",
            "take" to "drop",
            "drop" to "take",
            "takeLast" to "dropLast",
            "dropLast" to "takeLast",
            "isEmpty" to "isNotEmpty",
            "isNotEmpty" to "isEmpty",
            "min" to "max",
            "max" to "min",
            "minBy" to "maxBy",
            "maxBy" to "minBy",
            "minOf" to "maxOf",
            "maxOf" to "minOf"
        )

        private val COLLECTION_TYPES = setOf(
            "kotlin.collections.Iterable",
            "kotlin.collections.Collection",
            "kotlin.collections.List",
            "kotlin.collections.MutableCollection",
            "kotlin.collections.MutableList",
            "kotlin.collections.Set",
            "kotlin.collections.MutableSet",
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
            "kotlin.Array",
            "kotlin.String"
        )
    }

    override fun matches(call: IrCall): Boolean {
        if (call.origin != null) return false
        if (!receiverTypeIsCollection(call)) return false
        return call.symbol.owner.name.asString() in SWAPS
    }

    private fun receiverTypeIsCollection(call: IrCall): Boolean {
        call.dispatchReceiver?.let { return isCollectionType(it.type) }
        // Extension functions: the receiver is the first parameter (kind ExtensionReceiver).
        val params = call.symbol.owner.parameters
        val extParam = params.firstOrNull { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.ExtensionReceiver }
        return isCollectionType(extParam?.type ?: return false)
    }

    private fun isCollectionType(type: org.jetbrains.kotlin.ir.types.IrType): Boolean {
        val fqName = type.classFqName?.asString() ?: return false
        return fqName in COLLECTION_TYPES
    }

    override fun originalDescription(call: IrCall): String = call.symbol.owner.name.asString()

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val name = call.symbol.owner.name.asString()
        val replacementName = SWAPS[name] ?: return emptyList()

        val replacementFn = findFunction(call, replacementName, context) ?: return emptyList()
        // For extension functions the receiver is argument 0.
        val argStart = if (call.dispatchReceiver == null) 1 else 0
        val args = call.arguments.drop(argStart).map { it?.deepCopyWithSymbols() }
        val receiver = call.dispatchReceiver ?: call.arguments.getOrNull(0)

        return listOf(
            MutationOperator.Variant(replacementName) {
                context.builder.irCall(replacementFn).also { newCall ->
                    if (call.dispatchReceiver != null) {
                        newCall.dispatchReceiver = receiver!!.deepCopyWithSymbols()
                        args.forEachIndexed { i, a -> newCall.arguments[i] = a }
                    } else {
                        newCall.arguments[0] = receiver!!.deepCopyWithSymbols()
                        args.forEachIndexed { i, a -> newCall.arguments[i + 1] = a }
                    }
                }
            }
        )
    }

    private fun findFunction(
        original: IrCall,
        name: String,
        context: MutationContext
    ): IrSimpleFunctionSymbol? {
        val owner = original.symbol.owner
        // Member functions (isEmpty, indexOf, ...) have the class as parent.
        val parentClass = owner.parent as? org.jetbrains.kotlin.ir.declarations.IrClass
        val parentClassId = parentClass?.classId
        if (parentClassId != null) {
            return context.pluginContext.referenceFunctions(CallableId(parentClassId, Name.identifier(name)))
                .firstOrNull()
        }
        // Extension functions (filter, min, any, ...) live as top-level functions in a
        // package fragment. Find a sibling with the replacement name on the same package.
        val packageFragment = (owner.parent as? org.jetbrains.kotlin.ir.declarations.IrPackageFragment)
            ?: ((owner.parent as? org.jetbrains.kotlin.ir.declarations.IrDeclaration)
                ?.parent as? org.jetbrains.kotlin.ir.declarations.IrPackageFragment)
            ?: return null
        return packageFragment.declarations
            .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrFile>()
            .flatMap { it.declarations.asSequence() }
            .filterIsInstance<org.jetbrains.kotlin.ir.declarations.IrSimpleFunction>()
            .firstOrNull { it.name.asString() == name }
            ?.symbol
    }
}
