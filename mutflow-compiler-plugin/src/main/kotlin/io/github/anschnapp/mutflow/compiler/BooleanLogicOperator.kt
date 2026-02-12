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
 * Mutation operator for boolean logic swaps: && ↔ ||
 *
 * In Kotlin K2 IR (2.3.0+), boolean operators are lowered to IrWhen expressions:
 * - `a && b` → IrWhen(origin=ANDAND): when { a -> b; else -> false }
 * - `a || b` → IrWhen(origin=OROR):   when { a -> true; else -> b }
 *
 * Mutation approach (swap branch results):
 * - && → ||: change result from b to true, change else from false to b
 * - || → &&: change result from true to b, change else from b to false
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BooleanLogicOperator : WhenMutationOperator {

    override fun matches(whenExpr: IrWhen): Boolean {
        return whenExpr.origin == IrStatementOrigin.ANDAND ||
            whenExpr.origin == IrStatementOrigin.OROR
    }

    override fun originalDescription(whenExpr: IrWhen): String {
        return when (whenExpr.origin) {
            IrStatementOrigin.ANDAND -> "&&"
            IrStatementOrigin.OROR -> "||"
            else -> "?"
        }
    }

    override fun variants(whenExpr: IrWhen, context: MutationContext): List<MutationOperator.Variant> {
        // Validate structure: expect exactly 2 branches (condition + else)
        if (whenExpr.branches.size != 2) return emptyList()

        val firstBranch = whenExpr.branches[0]
        val elseBranch = whenExpr.branches[1]
        val booleanType = context.pluginContext.irBuiltIns.booleanType

        return when (whenExpr.origin) {
            // && → ||
            // Original: when(ANDAND) { a -> b;    else -> false }
            // Mutated:  when         { a -> true;  else -> b     }
            IrStatementOrigin.ANDAND -> {
                val condition = firstBranch.condition
                val secondOperand = firstBranch.result

                listOf(MutationOperator.Variant("||") {
                    IrWhenImpl(
                        startOffset = whenExpr.startOffset,
                        endOffset = whenExpr.endOffset,
                        type = booleanType,
                        origin = null
                    ).apply {
                        branches += IrBranchImpl(
                            startOffset = firstBranch.startOffset,
                            endOffset = firstBranch.endOffset,
                            condition = condition.deepCopyWithSymbols(),
                            result = IrConstImpl.boolean(
                                firstBranch.result.startOffset,
                                firstBranch.result.endOffset,
                                booleanType,
                                true
                            )
                        )
                        branches += IrElseBranchImpl(
                            startOffset = elseBranch.startOffset,
                            endOffset = elseBranch.endOffset,
                            condition = IrConstImpl.boolean(
                                elseBranch.condition.startOffset,
                                elseBranch.condition.endOffset,
                                booleanType,
                                true
                            ),
                            result = secondOperand.deepCopyWithSymbols()
                        )
                    }
                })
            }

            // || → &&
            // Original: when(OROR)   { a -> true;  else -> b     }
            // Mutated:  when         { a -> b;      else -> false }
            IrStatementOrigin.OROR -> {
                val condition = firstBranch.condition
                val secondOperand = elseBranch.result

                listOf(MutationOperator.Variant("&&") {
                    IrWhenImpl(
                        startOffset = whenExpr.startOffset,
                        endOffset = whenExpr.endOffset,
                        type = booleanType,
                        origin = null
                    ).apply {
                        branches += IrBranchImpl(
                            startOffset = firstBranch.startOffset,
                            endOffset = firstBranch.endOffset,
                            condition = condition.deepCopyWithSymbols(),
                            result = secondOperand.deepCopyWithSymbols()
                        )
                        branches += IrElseBranchImpl(
                            startOffset = elseBranch.startOffset,
                            endOffset = elseBranch.endOffset,
                            condition = IrConstImpl.boolean(
                                elseBranch.condition.startOffset,
                                elseBranch.condition.endOffset,
                                booleanType,
                                true
                            ),
                            result = IrConstImpl.boolean(
                                elseBranch.result.startOffset,
                                elseBranch.result.endOffset,
                                booleanType,
                                false
                            )
                        )
                    }
                })
            }

            else -> emptyList()
        }
    }
}
