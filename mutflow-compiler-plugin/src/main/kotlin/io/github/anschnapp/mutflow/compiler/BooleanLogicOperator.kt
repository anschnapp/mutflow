package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for boolean logic swaps: `&&` ↔ `||`.
 *
 * Handles BOTH IR representations of `&&`/`||`:
 * - **K2 IR (full Gradle pipeline)**: lowered to `IrWhen` with origin
 *   [IrStatementOrigin.ANDAND] / [IrStatementOrigin.OROR]:
 *   `a && b` → `when { a -> b; else -> false }`
 * - **Intrinsic-call form (isolated CLI compilation)**: an `IrCall` to the
 *   `ANDAND` / `OROR` intrinsic with both operands as value arguments.
 *
 * The operator is registered in both the call and when operator lists; only one
 * form is present in any given compilation, so exactly one mutation point is
 * generated per `&&`/`||`.
 *
 * Mirrors pitest's `NEGATE_CONDITIONALS` for boolean logic and Stryker's
 * `LogicalOperator` mutator.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class BooleanLogicOperator : MutationOperator, WhenMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "BOOLEAN_LOGIC",
        name = "BooleanLogic",
        description = "Swap && ↔ ||",
        group = MutatorGroup.BOOLEAN,
        status = MutatorStatus.STABLE
    )

    // --- IrCall form (intrinsic call: ANDAND / OROR) ---

    override fun matches(call: IrCall): Boolean {
        val name = call.symbol.owner.name.asString()
        return name == "ANDAND" || name == "OROR"
    }

    override fun originalDescription(call: IrCall): String =
        if (call.symbol.owner.name.asString() == "ANDAND") "&&" else "||"

    override fun variants(call: IrCall, context: MutationContext): List<MutationOperator.Variant> {
        val builtIns = context.pluginContext.irBuiltIns
        val isAnd = call.symbol.owner.name.asString() == "ANDAND"
        val replacementSymbol = if (isAnd) builtIns.ororSymbol else builtIns.andandSymbol
        val description = if (isAnd) "||" else "&&"

        return listOf(
            MutationOperator.Variant(description) {
                context.builder.irCall(replacementSymbol).also { newCall ->
                    call.arguments.forEachIndexed { index, arg ->
                        if (arg != null) {
                            newCall.arguments[index] = arg.deepCopyWithSymbols()
                        }
                    }
                }
            }
        )
    }

    // --- IrWhen form (K2 lowering: when { a -> b; else -> false }) ---

    override fun matches(whenExpr: IrWhen): Boolean =
        whenExpr.origin == IrStatementOrigin.ANDAND || whenExpr.origin == IrStatementOrigin.OROR

    override fun originalDescription(whenExpr: IrWhen): String =
        when (whenExpr.origin) {
            IrStatementOrigin.ANDAND -> "&&"
            IrStatementOrigin.OROR -> "||"
            else -> "?"
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
