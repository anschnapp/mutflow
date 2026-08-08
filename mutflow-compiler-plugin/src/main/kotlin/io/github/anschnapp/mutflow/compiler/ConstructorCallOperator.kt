package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.makeNullable

/**
 * Mutation operator for constructor calls: replaces the constructed object with `null`.
 *
 * Mirrors pitest's `CONSTRUCTOR_CALLS` mutator. Detects tests that don't verify
 * object creation — e.g. a test that only checks the constructor doesn't throw
 * will pass even when the object is never actually built.
 *
 * The null constant uses the nullable form of the constructed type; the enclosing
 * `when` keeps the original (non-nullable) type, so an active mutant surfaces as a
 * null where a real object was expected.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ConstructorCallOperator : ConstructorCallMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "CONSTRUCTOR_CALL",
        name = "ConstructorCall",
        description = "Replace constructor call with null",
        group = MutatorGroup.CALL,
        status = MutatorStatus.STABLE
    )

    override fun matches(call: IrConstructorCall): Boolean = true

    override fun originalDescription(call: IrConstructorCall): String =
        call.symbol.owner.name.asString()

    override fun variants(call: IrConstructorCall, context: MutationContext): List<MutationOperator.Variant> {
        val nullableType = call.type.makeNullable()
        return listOf(
            MutationOperator.Variant("null") {
                IrConstImpl.constNull(call.startOffset, call.endOffset, nullableType)
            }
        )
    }
}
