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
 * Argument layout: for a dispatch member call (`a.f(b, c)`) `call.arguments[0]`
 * is the dispatch receiver; for an extension call (`xs.f(b)`) `call.arguments[0]`
 * is the extension receiver. Both are excluded from the propagable value args.
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
     * Returns the value arguments (excluding the dispatch receiver and, for
     * extension functions, the extension receiver at argument 0) as
     * (expression, argumentIndex) pairs.
     */
    private fun valueArguments(call: IrCall): List<Pair<IrExpression, Int>> {
        val start = when {
            call.dispatchReceiver != null -> 1 // dispatch receiver occupies arguments[0]
            call.symbol.owner.parameters.any { it.kind == IrParameterKind.ExtensionReceiver } -> 1
            else -> 0
        }
        return call.arguments.drop(start).mapIndexedNotNull { i, expr ->
            if (expr != null) expr to (i + start) else null
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
