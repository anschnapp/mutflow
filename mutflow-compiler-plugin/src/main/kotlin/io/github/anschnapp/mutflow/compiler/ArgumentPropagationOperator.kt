package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator that propagates a call argument into another argument slot.
 *
 * Mirrors pitest's `ARGUMENT_PROPAGATION` (experimental). For a call `f(a, b)`
 * the variants replace one argument with a copy of another of the same type:
 * - `f(a, b)` → `f(a, a)` (propagate the first value arg into the second)
 * - `f(a, b)` → `f(b, b)` (propagate the second value arg into the first)
 *
 * Scoped to regular method/function calls (`origin == null`) so operator calls
 * (`a + b`, `a > b`, ...) are left to their dedicated operators. Requires at
 * least two value arguments whose types are equal, so the replacement typechecks
 * on every backend.
 *
 * Argument layout: `call.arguments` is positionally aligned with
 * `call.symbol.owner.parameters`, so dispatch receiver, context parameters,
 * and extension receiver slots (in any combination) are identified by kind
 * and excluded from the propagable value args.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ArgumentPropagationOperator : MutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "ARGUMENT_PROPAGATION",
        name = "ArgumentPropagation",
        description = "Propagate one argument into another (f(a,b) → f(a,a) / f(b,b))",
        group = MutatorGroup.CALL,
        status = MutatorStatus.EXPERIMENTAL
    )

    override fun matches(call: IrCall): Boolean {
        // Only regular calls (operators and property getters have non-null origins).
        if (call.origin != null) return false
        val valueArgs = valueArguments(call)
        return valueArgs.size >= 2 && sameType(valueArgs[0].first, valueArgs[1].first)
    }

    override fun originalDescription(call: IrCall): String =
        call.symbol.owner.name.asString()

    /**
     * Returns the value arguments (excluding dispatch receiver, context
     * parameters, and extension receiver, regardless of which combination is
     * present) as (expression, argumentIndex) pairs. `call.arguments` is
     * positionally aligned with `call.symbol.owner.parameters`, so each slot's
     * kind is looked up directly rather than inferred from a single offset.
     */
    private fun valueArguments(call: IrCall): List<Pair<IrExpression, Int>> {
        val kinds = call.symbol.owner.parameters.map { it.kind }
        return call.arguments.mapIndexedNotNull { i, expr ->
            if (expr != null && kinds.getOrNull(i) == IrParameterKind.Regular) expr to i else null
        }
    }

    /** Structural type equality via the class FQN (IrType `==` is unreliable across backends). */
    private fun sameType(a: IrExpression, b: IrExpression): Boolean =
        a.type.classFqName == b.type.classFqName

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val valueArgs = valueArguments(call)
        if (valueArgs.size < 2) return emptyList()
        val (arg0, idx0) = valueArgs[0]
        val (arg1, idx1) = valueArgs[1]
        if (!sameType(arg0, arg1)) return emptyList()

        // f(a, b) → f(a, a): copy arg0 into slot 1.
        val propagateFirst = MutationOperator.Variant("${call.symbol.owner.name.asString()}(arg->${idx1})") {
            val newCall = call.deepCopyWithSymbols()
            newCall.arguments[idx1] = arg0.deepCopyWithSymbols()
            newCall
        }
        // f(a, b) → f(b, b): copy arg1 into slot 0.
        val propagateSecond = MutationOperator.Variant("${call.symbol.owner.name.asString()}(arg->${idx0})") {
            val newCall = call.deepCopyWithSymbols()
            newCall.arguments[idx0] = arg1.deepCopyWithSymbols()
            newCall
        }
        return listOf(propagateFirst, propagateSecond)
    }
}
