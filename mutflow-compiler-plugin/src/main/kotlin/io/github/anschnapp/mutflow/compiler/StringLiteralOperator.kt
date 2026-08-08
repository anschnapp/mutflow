package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isString

/**
 * Mutation operator for string literal mutations.
 *
 * Replaces a non-empty string literal with the empty string, and an empty
 * string literal with a filled value. This detects tests that don't verify
 * the actual string content (only checking for non-empty / non-null).
 *
 * Mirrors Stryker's `StringLiteral` mutator and pitest's `EMPTY_RETURNS` for
 * strings.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class StringLiteralOperator : ConstMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "STRING_LITERAL",
        name = "StringLiteral",
        description = "Replace string literal with empty string",
        group = MutatorGroup.STRING,
        status = MutatorStatus.STABLE
    )

    override fun matches(const: IrConst): Boolean =
        const.type.isString() && const.value is String

    override fun originalDescription(const: IrConst): String =
        "\"${const.value}\""

    override fun variants(const: IrConst, context: MutationContext): List<MutationOperator.Variant> {
        val value = const.value as? String ?: return emptyList()
        val stringType = context.pluginContext.irBuiltIns.stringType

        return if (value.isEmpty()) {
            // Empty string → filled value
            listOf(
                MutationOperator.Variant("\"A\"") {
                    IrConstImpl.string(const.startOffset, const.endOffset, stringType, "A")
                }
            )
        } else {
            // Non-empty string → empty string
            listOf(
                MutationOperator.Variant("\"\"") {
                    IrConstImpl.string(const.startOffset, const.endOffset, stringType, "")
                }
            )
        }
    }
}
