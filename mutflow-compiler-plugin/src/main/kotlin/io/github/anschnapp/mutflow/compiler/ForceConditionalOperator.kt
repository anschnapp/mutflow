package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator that forces an `if` condition to `true` / `false`.
 *
 * In Kotlin IR, `if (c) a else b` is an [IrWhen] with origin
 * [IrStatementOrigin.IF] and two branches: `{ c -> a; else -> b }`. The
 * mutation replaces the condition with a constant so the branch is always
 * taken:
 * - `true`  → always take the then-branch
 * - `false` → always take the else-branch
 *
 * Mirrors pitest's `NEGATE_CONDITIONALS` (force-true/false variants) and
 * Stryker's `ConditionalExpression` mutator.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ForceConditionalOperator : WhenMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "FORCE_CONDITIONAL",
        name = "ForceConditional",
        description = "Force if condition to true/false",
        group = MutatorGroup.CONTROL_FLOW,
        status = MutatorStatus.STABLE
    )

    override fun matches(whenExpr: IrWhen): Boolean =
        whenExpr.origin == IrStatementOrigin.IF

    override fun originalDescription(whenExpr: IrWhen): String = "if"

    override fun variants(whenExpr: IrWhen, context: MutationContext): List<MutationOperator.Variant> {
        // Expect exactly 2 branches: condition + else.
        if (whenExpr.branches.size != 2) return emptyList()

        val firstBranch = whenExpr.branches[0]
        val elseBranch = whenExpr.branches[1]
        val booleanType = context.pluginContext.irBuiltIns.booleanType

        fun forcedWhen(conditionValue: Boolean): IrWhenImpl = IrWhenImpl(
            startOffset = whenExpr.startOffset,
            endOffset = whenExpr.endOffset,
            type = whenExpr.type,
            origin = null
        ).apply {
            branches += IrBranchImpl(
                startOffset = firstBranch.startOffset,
                endOffset = firstBranch.endOffset,
                condition = IrConstImpl.boolean(
                    firstBranch.condition.startOffset,
                    firstBranch.condition.endOffset,
                    booleanType,
                    conditionValue
                ),
                result = firstBranch.result.deepCopyWithSymbols()
            )
            branches += IrElseBranchImpl(
                startOffset = elseBranch.startOffset,
                endOffset = elseBranch.endOffset,
                condition = elseBranch.condition.deepCopyWithSymbols(),
                result = elseBranch.result.deepCopyWithSymbols()
            )
        }

        return listOf(
            MutationOperator.Variant("true") { forcedWhen(true) },
            MutationOperator.Variant("false") { forcedWhen(false) }
        )
    }
}
