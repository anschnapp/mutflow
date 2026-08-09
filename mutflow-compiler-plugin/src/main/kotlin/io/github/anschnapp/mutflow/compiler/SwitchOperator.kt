package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrBranch
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for subject `when` expressions (`switch`).
 *
 * In common IR a subject `when (x) { ... }` lowers to an [IrWhen] with
 * origin [IrStatementOrigin.WHEN]: each case is a branch whose condition is an
 * `EQEQ` comparison, and the trailing `else` is a branch whose condition is a
 * `true` constant. This operator produces:
 * - **case swap** — swap the first two case branches (pitest `SWITCH_MUTATOR`),
 * - **remove first case** — drop the first case branch so its subject falls
 *   through to the next matching case or the `else` (pitest `REMOVE_SWITCH`).
 *
 * Experimental: reordering switch branches can be high-noise, and the variants
 * require at least two non-else branches. Must be opted into explicitly.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class SwitchOperator : WhenMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "SWITCH",
        name = "Switch",
        description = "Swap / remove when (switch) branches",
        group = MutatorGroup.CONTROL_FLOW,
        status = MutatorStatus.EXPERIMENTAL
    )

    override fun matches(whenExpr: IrWhen): Boolean {
        if (whenExpr.origin != IrStatementOrigin.WHEN) return false
        return nonElseBranches(whenExpr).size >= 2
    }

    override fun originalDescription(whenExpr: IrWhen): String = "when"

    /** Returns the branches that are not the trailing `else` (true-const condition). */
    private fun nonElseBranches(whenExpr: IrWhen): List<IrBranch> {
        val branches = whenExpr.branches
        // The else branch has a `true` constant condition (or is an IrElseBranchImpl).
        return branches.filterNot { isElse(it) }
    }

    private fun isElse(branch: IrBranch): Boolean {
        val cond = branch.condition
        val isTrueConst = cond is org.jetbrains.kotlin.ir.expressions.IrConst &&
            cond.value == true
        return branch is IrElseBranchImpl || isTrueConst
    }

    override fun variants(whenExpr: IrWhen, context: MutationContext): List<MutationOperator.Variant> {
        val branches = whenExpr.branches
        val nonElse = nonElseBranches(whenExpr)
        if (nonElse.size < 2) return emptyList()

        val resultType = whenExpr.type

        // Variant 1: swap the first two case branches.
        val swap = MutationOperator.Variant("swap first two cases") {
            val newBranches = branches.toMutableList()
            val i = branches.indexOf(nonElse[0])
            val j = branches.indexOf(nonElse[1])
            val tmp = newBranches[i]
            newBranches[i] = newBranches[j]
            newBranches[j] = tmp
            rebuildWhen(whenExpr, newBranches)
        }

        // Variant 2: remove the first case branch entirely.
        val remove = MutationOperator.Variant("remove first case") {
            val idx = branches.indexOf(nonElse[0])
            val newBranches = branches.toMutableList().apply { removeAt(idx) }
            rebuildWhen(whenExpr, newBranches)
        }

        return listOf(swap, remove)
    }

    private fun rebuildWhen(original: IrWhen, branches: List<IrBranch>): IrWhen =
        IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = original.type,
            origin = null
        ).apply {
            for (branch in branches) {
                when (branch) {
                    is IrElseBranchImpl -> this.branches += IrElseBranchImpl(
                        branch.startOffset,
                        branch.endOffset,
                        branch.condition.deepCopyWithSymbols(),
                        branch.result.deepCopyWithSymbols()
                    )
                    else -> this.branches += IrBranchImpl(
                        branch.startOffset,
                        branch.endOffset,
                        branch.condition.deepCopyWithSymbols(),
                        branch.result.deepCopyWithSymbols()
                    )
                }
            }
        }
}
