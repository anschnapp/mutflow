package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Replaces `a?.b` with `a!!.b` — i.e. drops the null guard.
 *
 * The safe call lowers to a 2-branch when: branch 0 is the member access, branch 1
 * the `null` result. We take branch 0's result. Origin detection mirrors
 * [ElvisOperator] (on the enclosing block in common IR, on the when when folded).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class SafeCallOperator : WhenMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "SAFE_CALL",
        name = "SafeCall",
        description = "Replace a?.b with a!!.b (drop null guard)",
        group = MutatorGroup.KOTLIN_SPECIFIC,
        status = MutatorStatus.STABLE
    )

    override fun matches(whenExpr: IrWhen): Boolean {
        if (whenExpr.branches.size != 2) return false
        return whenExpr.origin?.debugName == "FOLDED_SAFE_CALL" ||
                EnclosingOriginProvider.currentOrigin == "SAFE_CALL" ||
                EnclosingOriginProvider.currentOrigin == "FOLDED_SAFE_CALL"
    }

    override fun originalDescription(whenExpr: IrWhen): String = "?."

    override fun variants(whenExpr: IrWhen, context: MutationContext): List<MutationOperator.Variant> {
        val nonNullAccess = whenExpr.branches[0].result ?: return emptyList()
        return listOf(
            MutationOperator.Variant("a!!.b") {
                nonNullAccess.deepCopyWithSymbols()
            }
        )
    }
}
