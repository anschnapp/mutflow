package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Replaces `a ?: b` with `a` or `b`.
 *
 * In K2 IR the elvis lowers to a 2-branch when: the subject in branch 0, the
 * fallback in branch 1. The origin lives on the enclosing block (common IR)
 * or on the when itself (JVM-folded), which is why we also check
 * [EnclosingOriginProvider]. The subject is `T?`, so the `a` variant goes
 * through `checkNotNull` to keep the when's non-null type happy.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ElvisOperator : WhenMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "ELVIS",
        name = "Elvis",
        description = "Replace a ?: b with a / b",
        group = MutatorGroup.KOTLIN_SPECIFIC,
        status = MutatorStatus.STABLE
    )

    override fun matches(whenExpr: IrWhen): Boolean {
        if (whenExpr.branches.size != 2) return false
        return whenExpr.origin?.debugName == "FOLDED_ELVIS" ||
                EnclosingOriginProvider.currentOrigin == "ELVIS" ||
                EnclosingOriginProvider.currentOrigin == "FOLDED_ELVIS"
    }

    override fun originalDescription(whenExpr: IrWhen): String = "?:"

    override fun variants(whenExpr: IrWhen, context: MutationContext): List<MutationOperator.Variant> {
        val subject = whenExpr.branches[0].result ?: return emptyList()
        val fallback = whenExpr.branches[1].result ?: return emptyList()
        val checkNotNull = context.pluginContext.irBuiltIns.checkNotNullSymbol

        return listOf(
            MutationOperator.Variant("b") {
                fallback.deepCopyWithSymbols()
            },
            MutationOperator.Variant("a") {
                val notNullType = subject.type.makeNotNull()
                context.builder.irCall(checkNotNull).also {
                    it.arguments[0] = subject.deepCopyWithSymbols()
                    it.typeArguments[0] = notNullType
                    it.type = notNullType
                }
            }
        )
    }
}
