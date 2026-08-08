package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isBoolean

/**
 * Mutation operator for boolean constant flips: `true` ↔ `false`.
 *
 * Replaces a boolean literal with its negation to detect tests that don't
 * verify the actual boolean value. Mirrors pitest's `INVERT_NEGS` for boolean
 * constants and Stryker's `BooleanLiteral` mutator.
 *
 * Note: boolean *returns* are handled by [BooleanReturnOperator]; this operator
 * targets boolean literals anywhere else (initializers, arguments, conditions).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BooleanConstOperator : ConstMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "BOOLEAN_CONST",
        name = "BooleanConst",
        description = "Flip boolean literal true ↔ false",
        group = MutatorGroup.BOOLEAN,
        status = MutatorStatus.STABLE
    )

    override fun matches(const: IrConst): Boolean =
        const.type.isBoolean() && const.value is Boolean

    override fun originalDescription(const: IrConst): String =
        const.value.toString()

    override fun variants(const: IrConst, context: MutationContext): List<MutationOperator.Variant> {
        val value = const.value as? Boolean ?: return emptyList()
        val flipped = !value
        val booleanType = context.pluginContext.irBuiltIns.booleanType

        return listOf(
            MutationOperator.Variant(flipped.toString()) {
                IrConstImpl.boolean(
                    const.startOffset,
                    const.endOffset,
                    booleanType,
                    flipped
                )
            }
        )
    }
}
