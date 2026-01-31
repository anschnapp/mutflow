package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR transformer that injects mutation points into @MutationTarget classes.
 *
 * Uses an extensible MutationOperator mechanism to support different
 * categories of mutations (comparisons, arithmetic, etc.).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class MutflowIrTransformer(
    private val pluginContext: IrPluginContext,
    private val operators: List<MutationOperator> = defaultOperators()
) : IrElementTransformerVoid() {

    companion object {
        private const val ENABLE_DEBUG_LOGGING = false

        fun defaultOperators(): List<MutationOperator> = listOf(
            RelationalComparisonOperator(),
            ConstantBoundaryOperator()
        )
    }

    private val mutationTargetFqName = FqName("io.github.anschnapp.mutflow.MutationTarget")
    private val suppressMutationsFqName = FqName("io.github.anschnapp.mutflow.SuppressMutations")
    private val mutationRegistryFqName = FqName("io.github.anschnapp.mutflow.MutationRegistry")

    private val mutationRegistryClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(mutationRegistryFqName))
    }

    private val checkFunction: IrSimpleFunctionSymbol? by lazy {
        val classId = ClassId.topLevel(mutationRegistryFqName)
        pluginContext.referenceFunctions(
            CallableId(classId, Name.identifier("check"))
        ).firstOrNull()
    }

    // State tracking during transformation
    private var currentFile: IrFile? = null
    private var currentClass: IrClass? = null
    private var currentFunction: IrSimpleFunction? = null
    private var isInMutationTarget = false
    private var isInSuppressedScope = false
    private var mutationPointCounter = 0

    override fun visitFile(declaration: IrFile): IrFile {
        val previousFile = currentFile
        currentFile = declaration
        val result = super.visitFile(declaration)
        currentFile = previousFile
        return result
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        val wasMutationTarget = isInMutationTarget
        val wasSuppressed = isInSuppressedScope
        val previousClass = currentClass

        isInMutationTarget = declaration.hasAnnotation(mutationTargetFqName)
        currentClass = declaration

        // Check for @SuppressMutations on the class
        if (isInMutationTarget && declaration.hasAnnotation(suppressMutationsFqName)) {
            isInSuppressedScope = true
        }

        if (isInMutationTarget && !isInSuppressedScope) {
            mutationPointCounter = 0
            if (ENABLE_DEBUG_LOGGING) {
                java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText(
                    "Found @MutationTarget class: ${declaration.fqNameWhenAvailable}\n"
                )
            }
        }

        val result = super.visitClass(declaration)

        isInMutationTarget = wasMutationTarget
        isInSuppressedScope = wasSuppressed
        currentClass = previousClass

        return result
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        val previousFunction = currentFunction
        val wasSuppressed = isInSuppressedScope

        currentFunction = declaration

        // Check for @SuppressMutations on the function
        if (isInMutationTarget && declaration.hasAnnotation(suppressMutationsFqName)) {
            isInSuppressedScope = true
        }

        val result = super.visitSimpleFunction(declaration)

        currentFunction = previousFunction
        isInSuppressedScope = wasSuppressed
        return result
    }

    override fun visitCall(expression: IrCall): IrExpression {
        // First, transform children (bottom-up for nested expressions)
        val transformed = super.visitCall(expression) as IrCall

        // Only transform if we're in a @MutationTarget class and not suppressed
        if (!isInMutationTarget || isInSuppressedScope) {
            return transformed
        }

        val fn = currentFunction ?: return transformed
        return transformWithOperators(transformed, fn, operators)
    }

    /**
     * Recursively applies matching operators to an expression.
     *
     * Each matching operator wraps the expression in a when block, with
     * the else branch passing to the next operator. This enables multiple
     * independent mutation points on the same expression (e.g., operator
     * mutation AND constant boundary mutation).
     */
    private fun transformWithOperators(
        original: IrCall,
        containingFunction: IrSimpleFunction,
        remainingOperators: List<MutationOperator>
    ): IrExpression {
        if (remainingOperators.isEmpty()) {
            return original.deepCopyWithSymbols()
        }

        val operator = remainingOperators.first()
        val rest = remainingOperators.drop(1)

        if (!operator.matches(original)) {
            return transformWithOperators(original, containingFunction, rest)
        }

        return transformWithOperator(original, containingFunction, operator, rest)
    }

    /**
     * Transforms an expression using the given mutation operator.
     *
     * Generates a when expression:
     * ```
     * when (MutationRegistry.check(pointId, variantCount, ...)) {
     *     0 -> variant0
     *     1 -> variant1
     *     ...
     *     else -> <recursively apply remaining operators>
     * }
     * ```
     */
    private fun transformWithOperator(
        original: IrCall,
        containingFunction: IrSimpleFunction,
        operator: MutationOperator,
        remainingOperators: List<MutationOperator>
    ): IrExpression {
        val checkFn = checkFunction ?: run {
            if (ENABLE_DEBUG_LOGGING) {
                java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText("checkFunction is NULL!\n")
            }
            return original
        }
        val registryClass = mutationRegistryClass ?: run {
            if (ENABLE_DEBUG_LOGGING) {
                java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText("mutationRegistryClass is NULL!\n")
            }
            return original
        }

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
        val context = MutationContext(pluginContext, builder)

        val variants = operator.variants(original, context)
        if (variants.isEmpty()) {
            // No variants from this operator, try remaining operators
            return transformWithOperators(original, containingFunction, remainingOperators)
        }

        val pointId = generatePointId()
        val variantCount = variants.size
        val sourceLocation = getSourceLocation(original)
        val originalOperator = operator.originalDescription(original)
        val variantOperators = variants.joinToString(",") { it.description }

        if (ENABLE_DEBUG_LOGGING) {
            java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText(
                "Transformed $originalOperator at $sourceLocation with variants: $variantOperators\n"
            )
        }

        return builder.irBlock(resultType = pluginContext.irBuiltIns.booleanType) {
            val checkCall = irCall(checkFn).also { call ->
                call.arguments[0] = irGetObject(registryClass)  // dispatch receiver
                call.arguments[1] = irString(pointId)           // pointId: String
                call.arguments[2] = irInt(variantCount)         // variantCount: Int
                call.arguments[3] = irString(sourceLocation)    // sourceLocation: String
                call.arguments[4] = irString(originalOperator)  // originalOperator: String
                call.arguments[5] = irString(variantOperators)  // variantOperators: String
            }
            val checkResult = irTemporary(checkCall, nameHint = "mutationCheck")

            +IrWhenImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                type = pluginContext.irBuiltIns.booleanType,
                origin = null
            ).apply {
                // Add branches for each variant
                variants.forEachIndexed { index, variant ->
                    branches += IrBranchImpl(
                        startOffset = original.startOffset,
                        endOffset = original.endOffset,
                        condition = irEquals(irGet(checkResult), irInt(index)),
                        result = variant.createExpression()
                    )
                }
                // Else: recursively apply remaining operators (or original if none left)
                branches += IrElseBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = irTrue(),
                    result = transformWithOperators(original, containingFunction, remainingOperators)
                )
            }
        }
    }

    private fun generatePointId(): String {
        val className = currentClass?.fqNameWhenAvailable?.asString() ?: "unknown"
        return "${className}_${mutationPointCounter++}"
    }

    /**
     * Extracts source location from an IR expression.
     * Returns format like "Calculator.kt:5" for IntelliJ clickable links.
     */
    private fun getSourceLocation(expression: IrExpression): String {
        val file = currentFile ?: return "unknown:0"
        val fileName = file.fileEntry.name.substringAfterLast('/')
        val lineNumber = file.fileEntry.getLineNumber(expression.startOffset) + 1
        return "$fileName:$lineNumber"
    }
}
