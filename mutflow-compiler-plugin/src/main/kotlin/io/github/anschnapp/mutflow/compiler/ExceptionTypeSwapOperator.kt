package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrThrow
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Mutation operator that swaps exception types at throw sites.
 *
 * When source code throws one exception type (e.g. `IllegalArgumentException`),
 * this operator generates a variant that throws a sibling exception type
 * (e.g. `IllegalStateException`) instead. Sibling pairs are chosen so that
 * neither type is a subtype of the other — both extend `RuntimeException`.
 *
 * The operator matches on `IrThrow` nodes and mutates over the constructor
 * arguments: they are evaluated once, and the original constructor call and the
 * sibling exception's constructor call both take them by position.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ExceptionTypeSwapOperator : MutationOperator<IrThrow> {

    /**
     * Maps source exception FQNs to sibling replacement FQNs.
     *
     * Uses `kotlin.*` FQNs (typealiased to `java.lang.*` on JVM, available
     * on all platforms including Kotlin/Native). Each pair is chosen so that
     * neither class is a subtype of the other — both extend `RuntimeException`
     * as an immediate supertype, ensuring valid IR type substitution at throw sites.
     *
     * Note: `NumberFormatException` extends `IllegalArgumentException`, so pairing
     * them would be subtype-equivalent. We pair with `IllegalStateException` instead.
     */
    private companion object {
        internal val EXCEPTION_SWAPS: Map<String, String> = mapOf(
            "kotlin.IllegalArgumentException" to "kotlin.IllegalStateException",
            "kotlin.IllegalStateException" to "kotlin.IllegalArgumentException",
            "kotlin.NullPointerException" to "kotlin.IllegalArgumentException",
            "kotlin.IndexOutOfBoundsException" to "kotlin.IllegalStateException",
            "kotlin.UnsupportedOperationException" to "kotlin.IllegalStateException",
            "kotlin.ClassCastException" to "kotlin.IllegalArgumentException",
            "kotlin.NumberFormatException" to "kotlin.IllegalStateException",
            "kotlin.ArithmeticException" to "kotlin.IllegalStateException",
            "kotlin.NoSuchElementException" to "kotlin.IllegalStateException",
        )
    }

    override fun matches(node: IrThrow): Boolean {
        val throwExpr = node
        // Precautionary guard against throws that have no source span: a missing
        // or zero-width offset means the node was synthesized rather than written
        // by a developer, and mutating it would be meaningless.
        //
        // As of Kotlin 2.4 this guard is unreachable. Constructs that eventually
        // become throws (`!!`, `TODO()`, `require`/`check`, exhaustive `when`
        // without else) are still IrCall nodes when IR plugin extensions run;
        // they only become IrThrow in later backend lowerings. Verified by dumping
        // IR at this phase: `!!` is CALL 'CHECK_NOT_NULL' origin=EXCLEXCL, and an
        // exhaustive `when` ends in CALL 'noWhenBranchMatchedException()'.
        //
        // Kept because it costs nothing and lowering order is not a stable contract.
        // Note this differs from BooleanReturnOperator, whose equivalent check IS
        // load-bearing: synthetic IrReturn (expression-bodied functions) does exist
        // at this phase.
        if (throwExpr.startOffset == UNDEFINED_OFFSET || throwExpr.startOffset < 0) {
            return false
        }
        if (throwExpr.startOffset == throwExpr.endOffset) {
            return false
        }

        // Only mutate constructor calls directly thrown (not variables):
        // val e = IllegalStateException(); throw e
        // In the above, the thrown expression is IrGetValue, not IrConstructorCall.
        val thrownExpr = throwExpr.value ?: return false
        return thrownExpr is IrConstructorCall
    }

    private fun originalDescription(thrownExpr: IrConstructorCall): String {
        val constructedType = thrownExpr.symbol.owner.returnType
        val classSymbol = constructedType.classOrNull ?: return "?"
        return classSymbol.owner.fqNameWhenAvailable?.asString()?.substringAfterLast('.') ?: "?"
    }

    override fun mutation(node: IrThrow, context: MutationContext): Mutation? {
        val thrownExpr = node.value as? IrConstructorCall ?: return null
        val sourceType = thrownExpr.symbol.owner.returnType
        val sourceSymbol = sourceType.classOrNull ?: return null
        val (_, targetFqName) = findSwapPair(sourceSymbol, context) ?: return null

        val targetClassId = ClassId.topLevel(FqName(targetFqName))
        val targetClassSymbol = context.pluginContext.referenceClass(targetClassId) ?: return null
        val targetClass = targetClassSymbol.owner

        val matchingConstructor = findMatchingConstructor(thrownExpr, targetClass) ?: return null

        val targetShortName = targetFqName.substringAfterLast('.')

        // Exception constructors have no dispatch or extension receivers, so value-parameter
        // indices map directly to argument list positions. Omitted (defaulted) arguments stay
        // omitted in both calls.
        val argumentIndices = thrownExpr.symbol.owner.parameters.indices.filter { thrownExpr.arguments[it] != null }

        return Mutation.OverOperands(
            originalDescription = originalDescription(thrownExpr),
            operands = argumentIndices.map { thrownExpr.arguments[it]!! },
            original = { operands ->
                argumentIndices.forEachIndexed { operand, index -> thrownExpr.arguments[index] = operands[operand] }
                thrownExpr
            },
            variants = listOf(
                // Just the target type: the display name is assembled as
                // "(file:line) <original> → <variant>", so repeating the source type
                // here would render as "ISE → ISE → IAE".
                Mutation.OverOperands.Variant(targetShortName) { operands ->
                    // irCall(IrConstructorSymbol) creates a constructor call with the
                    // return type computed from the constructor's class.
                    context.builder.irCall(matchingConstructor.symbol).also { call ->
                        argumentIndices.forEachIndexed { operand, index -> call.arguments[index] = operands[operand] }
                    }
                }
            )
        )
    }

    /**
     * Finds the swap pair for a source class symbol by resolving each [kotlin.*]
     * FQN in [EXCEPTION_SWAPS] through [IrPluginContext.referenceClass] and
     * comparing the resulting [IrClassSymbol]s. On JVM, kotlin.* typealiases
     * resolve to java.lang.* (or java.util.*), so symbol identity — not
     * FQName string comparison — is the correct matching strategy.
     */
    private fun findSwapPair(
        sourceSymbol: IrClassSymbol,
        context: MutationContext
    ): Pair<String, String>? {
        for ((sourceFqName, targetFqName) in EXCEPTION_SWAPS) {
            val classId = ClassId.topLevel(FqName(sourceFqName))
            val resolvedSymbol = context.pluginContext.referenceClass(classId) ?: continue
            if (resolvedSymbol == sourceSymbol) {
                return sourceFqName to targetFqName
            }
        }
        return null
    }

    /**
     * Finds a constructor on [targetClass] whose parameter types match the
     * argument types of the original constructor call.
     */
    private fun findMatchingConstructor(
        call: IrConstructorCall,
        targetClass: IrClass
    ): IrConstructor? {
        val sourceConstructor = call.symbol.owner
        val sourceParamTypes = sourceConstructor.parameters.map { it.type }

        return targetClass.constructors
            .firstOrNull { constructor ->
                val targetParamTypes = constructor.parameters.map { it.type }
                targetParamTypes.size == sourceParamTypes.size &&
                    targetParamTypes.zip(sourceParamTypes).all { (target, source) -> target.classifierOrNull == source.classifierOrNull }
            }
    }
}
