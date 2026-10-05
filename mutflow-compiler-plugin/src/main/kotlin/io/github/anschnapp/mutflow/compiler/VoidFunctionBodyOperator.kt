package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isPropertyAccessor

/**
 * Mutation operator that removes the body of Unit/void functions.
 *
 * Replaces the entire function body with an empty body (no-op), removing all
 * side effects. This catches tests that don't verify side effects - the mental
 * model of "what if this function did nothing?"
 *
 * Example:
 * ```
 * // Original
 * fun save(entity: Entity) {
 *     database.insert(entity)
 *     logger.info("Saved $entity")
 * }
 *
 * // Mutant: empty body
 * fun save(entity: Entity) { }
 * ```
 *
 * Only matches functions that:
 * - Return Unit
 * - Have a non-empty body
 * - Are not property accessors (getters/setters)
 *
 * The variant replaces the whole body block, whose statements the transformer keeps as the
 * original, with an empty block.
 */
class VoidFunctionBodyOperator : MutationOperator<IrSimpleFunction> {

    override fun matches(node: IrSimpleFunction): Boolean {
        val function = node
        if (!function.returnType.isUnit()) return false
        if (function.isPropertyAccessor) return false

        val body = function.body as? IrBlockBody ?: return false
        // Skip functions with empty bodies
        if (body.statements.isEmpty()) return false

        return true
    }

    override fun mutation(node: IrSimpleFunction, context: MutationContext): Mutation {
        val unitType = context.pluginContext.irBuiltIns.unitType
        return Mutation.Replace(
            originalDescription = "${node.name}()",
            variants = listOf(
                Mutation.Replace.Variant("removed") {
                    IrBlockImpl(node.startOffset, node.endOffset, unitType)
                }
            )
        )
    }
}
