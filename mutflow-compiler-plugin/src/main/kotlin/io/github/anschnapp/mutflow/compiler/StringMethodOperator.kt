package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * Mutation operator for String method calls.
 *
 * Mirrors Stryker's `StringMethod` / `StringMethodToConstant` mutators:
 * - `s.endsWith(x)` ↔ `s.startsWith(x)`
 * - `s.toUpperCase()` ↔ `s.toLowerCase()`
 * - `s.trim()` → `""` (StringMethodToConstant)
 *
 * Matched by receiver type `String` + method name, since these are regular
 * (origin == null) member calls with no dedicated IrStatementOrigin.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class StringMethodOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "STRING_METHOD",
        name = "StringMethod",
        description = "Swap endsWith↔startsWith, toUpperCase↔toLowerCase; trim→\"\"",
        group = MutatorGroup.STRING,
        status = MutatorStatus.STABLE
    )

    private companion object {
        /** name -> replacement name (also matching `$default` synthetic variants) */
        private val SWAPS = mapOf(
            "endsWith" to "startsWith",
            "startsWith" to "endsWith",
            "toUpperCase" to "toLowerCase",
            "toLowerCase" to "toUpperCase",
            "uppercase" to "lowercase",
            "lowercase" to "uppercase"
        )

        /** methods whose variant replaces the result with a constant */
        private val TO_CONSTANT = setOf("trim", "trimStart", "trimEnd")

        /**
         * Returns the "base" method name for a call, stripping the synthetic
         * `$default` suffix that Kotlin adds when default parameters are present.
         */
        private fun baseName(name: String): String =
            name.removeSuffix("\$default")
    }

    override fun matches(call: IrCall): Boolean {
        val name = call.symbol.owner.name.asString()
        if (!receiverTypeIsString(call)) return false
        return baseName(name) in SWAPS || baseName(name) in TO_CONSTANT
    }

    /** True if the call's receiver (dispatch or extension) is a String. */
    private fun receiverTypeIsString(call: IrCall): Boolean {
        call.dispatchReceiver?.let { return it.type.isString() }
        // Extension functions: the receiver is the first parameter (kind ExtensionReceiver).
        val params = call.symbol.owner.parameters
        val extParam = params.firstOrNull { it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.ExtensionReceiver }
        return extParam?.type?.isString() == true
    }

    /** Returns the receiver expression (dispatch receiver, or the extension receiver arg). */
    private fun receiverOf(call: IrCall): IrExpression? {
        call.dispatchReceiver?.let { return it }
        // For extension functions the receiver is the first argument.
        return call.arguments.getOrNull(0)
    }

    override fun originalDescription(call: IrCall): String = baseName(call.symbol.owner.name.asString())

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val receiver = receiverOf(call) ?: return emptyList()
        val name = baseName(call.symbol.owner.name.asString())

        // trim() → "" : replace the whole call with an empty string constant.
        if (name in TO_CONSTANT) {
            val stringType = call.type
            return listOf(
                MutationOperator.Variant("\"\"") {
                    IrConstImpl.string(call.startOffset, call.endOffset, stringType, "")
                }
            )
        }

        // endsWith↔startsWith, toUpperCase↔toLowerCase
        val replacementName = SWAPS[name] ?: return emptyList()
        val replacementFn = findFunction(call, replacementName, context) ?: return emptyList()
        // The extension receiver is argument 0; the remaining args are the value args.
        val argStart = if (call.dispatchReceiver == null) 1 else 0
        val args = call.arguments.drop(argStart).map { it?.deepCopyWithSymbols() }

        return listOf(
            MutationOperator.Variant(replacementName) {
                context.builder.irCall(replacementFn).also { newCall ->
                    if (call.dispatchReceiver != null) {
                        newCall.dispatchReceiver = receiver.deepCopyWithSymbols()
                        args.forEachIndexed { i, a -> newCall.arguments[i] = a }
                    } else {
                        // Extension: set extension receiver (arg 0) then value args.
                        newCall.arguments[0] = receiver.deepCopyWithSymbols()
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
        // Extension functions (endsWith, startsWith, uppercase, ...) carry an
        // ExtensionReceiver parameter even when owner.parent reports a receiver
        // class (fake override). Detect them first so we don't route through
        // `referenceFunctions` (which can fail outside a full IDE/compiler
        // environment when deserializing stdlib classes).
        val isExtension = owner.parameters.any {
            it.kind == org.jetbrains.kotlin.ir.declarations.IrParameterKind.ExtensionReceiver
        }
        if (isExtension) {
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
        // Member functions (toUpperCase, ...) have the class as parent.
        val parentClass = owner.parent as? org.jetbrains.kotlin.ir.declarations.IrClass
        val parentClassId = parentClass?.classId
        if (parentClassId != null) {
            return context.pluginContext.referenceFunctions(CallableId(parentClassId, Name.identifier(name)))
                .firstOrNull()
        }
        return null
    }
}
