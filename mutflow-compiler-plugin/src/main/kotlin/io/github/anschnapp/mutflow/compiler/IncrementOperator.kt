package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.Name

/**
 * Mutation operator for increment/decrement swaps: `++` ↔ `--`.
 *
 * Handles BOTH IR representations of `++`/`--`:
 * - **K2 IR (full Gradle pipeline)**: `a++` is a call to the `inc` / `dec`
 *   member function with the operand as dispatch receiver. The mutation swaps
 *   the call for its counterpart on the same declaring class (e.g. `Int.inc` ↔
 *   `Int.dec`).
 * - **Intrinsic-call form (isolated CLI compilation)**: `a++` is a call to the
 *   synthetic `int-postfix-incr-decr` / `int-prefix-incr-decr` intrinsic with
 *   the operand and a delta constant (`1`). The mutation negates the delta so
 *   `++` becomes `--` and vice versa.
 *
 * Mirrors pitest's `INCREMENTS` and Mull's `cxx_post_inc_to_post_dec` /
 * `cxx_pre_inc_to_pre_dec`.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class IncrementOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "INCREMENT",
        name = "Increment",
        description = "Swap ++ ↔ --",
        group = MutatorGroup.ARITHMETIC,
        status = MutatorStatus.STABLE
    )

    companion object {
        private val INCR_DECR_NAMES = setOf("inc", "dec")
        // Synthetic intrinsic names carry angle brackets (e.g. `<int-postfix-incr-decr>`).
        private val INTRINSIC_NAMES = setOf("<int-postfix-incr-decr>", "<int-prefix-incr-decr>")
    }

    override fun matches(call: IrCall): Boolean {
        val name = call.symbol.owner.name.asString()
        return name in INCR_DECR_NAMES || name in INTRINSIC_NAMES
    }

    override fun originalDescription(call: IrCall): String {
        val name = call.symbol.owner.name.asString()
        return when {
            name == "inc" -> "++"
            name == "dec" -> "--"
            name in INTRINSIC_NAMES -> {
                // The intrinsic name is the same for ++ and --; the delta sign
                // distinguishes them (++ has delta +1, -- has delta -1).
                val delta = call.arguments.getOrNull(1) as? IrConst
                if (delta != null && isNegative(delta)) "--" else "++"
            }
            else -> "?"
        }
    }

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val name = call.symbol.owner.name.asString()
        return when {
            name in INCR_DECR_NAMES -> memberFunctionVariants(call, name, context)
            name in INTRINSIC_NAMES -> intrinsicVariants(call, context)
            else -> emptyList()
        }
    }

    /**
     * K2 form: swap `inc` ↔ `dec` on the same declaring class.
     */
    private fun memberFunctionVariants(
        call: IrCall,
        name: String,
        context: MutationContext
    ): List<MutationOperator.Variant> {
        val replacementName = if (name == "inc") "dec" else "inc"
        val receiver = call.dispatchReceiver ?: return emptyList()

        val replacementFn = findFunction(call, replacementName, context) ?: return emptyList()

        return listOf(
            MutationOperator.Variant(replacementName) {
                context.builder.irCall(replacementFn).also {
                    it.dispatchReceiver = receiver.deepCopyWithSymbols()
                }
            }
        )
    }

    /**
     * Intrinsic-call form: negate the delta constant so `++` becomes `--`.
     */
    private fun intrinsicVariants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val delta = call.arguments.getOrNull(1) as? IrConst ?: return emptyList()
        val negated = negateConstant(delta) ?: return emptyList()
        val description = if (isNegative(delta)) "++" else "--"

        return listOf(
            MutationOperator.Variant(description) {
                context.builder.irCall(call.symbol).also { newCall ->
                    call.arguments.forEachIndexed { index, arg ->
                        if (arg != null) {
                            newCall.arguments[index] = arg.deepCopyWithSymbols()
                        }
                    }
                    newCall.arguments[1] = negated.deepCopyWithSymbols()
                }
            }
        )
    }

    private fun isNegative(constant: IrConst): Boolean = when (val value = constant.value) {
        is Int -> value < 0
        is Long -> value < 0
        is Short -> value < 0
        is Byte -> value < 0
        is Float -> value < 0
        is Double -> value < 0
        else -> false
    }

    private fun negateConstant(constant: IrConst): IrConst? {
        val startOffset = constant.startOffset
        val endOffset = constant.endOffset
        val type = constant.type

        return when (val value = constant.value) {
            is Int -> IrConstImpl.int(startOffset, endOffset, type, -value)
            is Long -> IrConstImpl.long(startOffset, endOffset, type, -value)
            is Short -> IrConstImpl.short(startOffset, endOffset, type, (-value).toShort())
            is Byte -> IrConstImpl.byte(startOffset, endOffset, type, (-value).toByte())
            is Float -> IrConstImpl.float(startOffset, endOffset, type, -value)
            is Double -> IrConstImpl.double(startOffset, endOffset, type, -value)
            else -> null
        }
    }

    /**
     * Finds the replacement function with the same signature as the original.
     * `inc`/`dec` take no value parameters, so — unlike ArithmeticOperator's
     * `plus`/`minus`, which have per-type overloads to disambiguate — matching
     * by declaring class plus parameter count (dispatch receiver only) is
     * sufficient to pick the right overload for primitives.
     */
    private fun findFunction(
        original: IrCall,
        replacementName: String,
        context: MutationContext
    ): IrSimpleFunctionSymbol? {
        val declaringClassId = (original.symbol.owner.parent as? IrClass)?.classId
            ?: return null
        val callableId = CallableId(declaringClassId, Name.identifier(replacementName))
        val candidates = context.pluginContext.referenceFunctions(callableId)
        val originalParamCount = original.symbol.owner.parameters.size
        return candidates.firstOrNull { fn -> fn.owner.parameters.size == originalParamCount }
            ?: candidates.firstOrNull()
    }
}
