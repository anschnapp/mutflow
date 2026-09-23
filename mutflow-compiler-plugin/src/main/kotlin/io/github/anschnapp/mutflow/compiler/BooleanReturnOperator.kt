package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.types.isBoolean

/**
 * Mutation operator for boolean return statements.
 *
 * Replaces the return value with constant true/false to detect tests that
 * don't properly verify both outcomes of a boolean function.
 *
 * Example:
 * ```
 * // Original
 * return x > 0 && x < 100
 *
 * // Variant 0: return true
 * // Variant 1: return false
 * ```
 *
 * If a test passes when the return is mutated to `true`, it means
 * the test never verifies the `false` case (and vice versa).
 *
 * Note: This operator only matches explicit return statements from source code,
 * not synthetic returns generated for expression-bodied functions.
 */
class BooleanReturnOperator : MutationOperator<IrReturn> {

    override fun matches(node: IrReturn): Boolean {
        val ret = node
        val value = ret.value

        // Skip synthetic returns (expression-bodied functions get synthetic IrReturn nodes)
        // These can be detected by:
        // 1. UNDEFINED_OFFSET (-1) or negative offsets
        // 2. Zero-width span (startOffset == endOffset) - synthetic returns don't span the "return" keyword
        if (ret.startOffset == UNDEFINED_OFFSET || ret.startOffset < 0) {
            return false
        }
        if (ret.startOffset == ret.endOffset) {
            // Zero-width return is synthetic (from expression-bodied function)
            return false
        }

        // Match if the return value is a boolean expression
        // Skip if it's already a constant (mutating `return true` to `true` is pointless)
        return value.type.isBoolean() && value !is IrConst
    }

    override fun mutation(node: IrReturn, context: MutationContext): Mutation {
        val value = node.value
        val builtIns = context.pluginContext.irBuiltIns

        fun constant(result: Boolean) = Mutation.Replace.Variant(result.toString()) {
            IrConstImpl.boolean(value.startOffset, value.endOffset, builtIns.booleanType, result)
        }

        return Mutation.Replace(
            originalDescription = "return ...",
            variants = listOf(constant(true), constant(false))
        )
    }
}
