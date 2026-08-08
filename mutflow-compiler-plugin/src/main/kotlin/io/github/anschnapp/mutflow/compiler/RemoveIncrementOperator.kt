package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator that removes an increment/decrement: `a++` → `a`.
 *
 * The `inc`/`dec` call is replaced by its operand, so the surrounding assignment
 * `a = a.inc()` becomes the no-op `a = a`. Detects tests that don't verify the
 * incremented value. Mirrors pitest's `REMOVE_INCREMENTS` (experimental).
 *
 * Handles both IR forms of `++`/`--`:
 * - **K2 IR (full Gradle pipeline)**: `a++` is a call to the `inc` / `dec`
 *   member function; the operand is the dispatch receiver.
 * - **Intrinsic-call form (isolated CLI compilation)**: `a++` is a call to the
 *   synthetic `<int-postfix-incr-decr>` / `<int-prefix-incr-decr>` intrinsic;
 *   the operand is `arguments[0]` and the delta constant is `arguments[1]`.
 *
 * Experimental: high-noise, must be opted into explicitly (not in the default
 * operator set).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class RemoveIncrementOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "REMOVE_INCREMENT",
        name = "RemoveIncrement",
        description = "Remove increment/decrement (a++ → a)",
        group = MutatorGroup.ARITHMETIC,
        status = MutatorStatus.EXPERIMENTAL
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
        val operand = when {
            name in INCR_DECR_NAMES -> call.dispatchReceiver
            name in INTRINSIC_NAMES -> call.arguments.getOrNull(0)
            else -> null
        } ?: return emptyList()

        return listOf(
            MutationOperator.Variant("noop") {
                operand.deepCopyWithSymbols()
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
}
