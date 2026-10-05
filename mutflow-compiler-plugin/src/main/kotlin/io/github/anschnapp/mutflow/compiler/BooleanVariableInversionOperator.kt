package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for boolean variable and parameter reads: `flag` → `!flag`
 *
 * The read is a leaf, so the variant simply negates a copy of it:
 * ```
 * when {
 *     MutationRegistry.check(...) == 0 -> !flag
 *     else -> flag
 * }
 * ```
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BooleanVariableInversionOperator : MutationOperator<IrGetValue> {

    override fun matches(node: IrGetValue): Boolean = node.type.isBoolean()

    override fun mutation(node: IrGetValue, context: MutationContext): Mutation {
        val name = node.symbol.owner.name.asString()
        val booleanNotSymbol = context.pluginContext.irBuiltIns.booleanNotSymbol

        return Mutation.Replace(
            originalDescription = name,
            variants = listOf(
                Mutation.Replace.Variant("!$name") {
                    context.builder.irCall(booleanNotSymbol).also {
                        it.dispatchReceiver = node.deepCopyWithSymbols()
                    }
                }
            )
        )
    }
}
