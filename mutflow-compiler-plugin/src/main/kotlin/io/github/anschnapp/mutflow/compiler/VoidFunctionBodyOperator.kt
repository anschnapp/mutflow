package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
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
 */
class VoidFunctionBodyOperator : FunctionBodyMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "VOID_FUNCTION_BODY",
        name = "VoidFunctionBody",
        description = "Empty the body of Unit-returning functions",
        group = MutatorGroup.CALL,
        status = MutatorStatus.STABLE
    )

    override fun matches(function: IrSimpleFunction): Boolean {
        if (!function.returnType.isUnit()) return false
        if (function.isPropertyAccessor) return false

        val body = function.body as? IrBlockBody ?: return false
        // Skip functions with empty bodies
        if (body.statements.isEmpty()) return false

        return true
    }

    override fun originalDescription(function: IrSimpleFunction): String {
        return "${function.name}()"
    }

    override fun variantCount(function: IrSimpleFunction): Int = 1

    override fun variantDescriptions(function: IrSimpleFunction): List<String> {
        return listOf("removed")
    }
}
