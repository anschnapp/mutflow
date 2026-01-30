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
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * IR transformer that injects mutation points into @MutationTarget classes.
 *
 * For the tracer bullet, this transforms integer comparison operators (>)
 * into mutation-aware when expressions.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class MutflowIrTransformer(
    private val pluginContext: IrPluginContext
) : IrElementTransformerVoid() {

    private val mutationTargetFqName = FqName("io.github.anschnapp.mutflow.MutationTarget")
    private val mutationRegistryFqName = FqName("io.github.anschnapp.mutflow.MutationRegistry")

    private val mutationRegistryClass: IrClassSymbol? by lazy {
        pluginContext.referenceClass(ClassId.topLevel(mutationRegistryFqName))
    }

    private val checkFunction: IrSimpleFunctionSymbol? by lazy {
        // check is a member function of MutationRegistry object
        // Look it up via the class ID
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
        val previousClass = currentClass

        isInMutationTarget = declaration.hasAnnotation(mutationTargetFqName)
        currentClass = declaration

        if (isInMutationTarget) {
            mutationPointCounter = 0
            if (ENABLE_DEBUG_LOGGING) {
                java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText(
                    "Found @MutationTarget class: ${declaration.fqNameWhenAvailable}\n"
                )
            }
        }

        val result = super.visitClass(declaration)

        isInMutationTarget = wasMutationTarget
        currentClass = previousClass

        return result
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        val previousFunction = currentFunction
        currentFunction = declaration

        val result = super.visitSimpleFunction(declaration)

        currentFunction = previousFunction
        return result
    }

    override fun visitCall(expression: IrCall): IrExpression {
        // First, transform children
        val transformed = super.visitCall(expression) as IrCall

        // Only transform if we're in a @MutationTarget class
        if (!isInMutationTarget) {
            return transformed
        }

        // Check for GT (greater than) origin - this is the `>` operator
        if (transformed.origin == IrStatementOrigin.GT) {
            val fn = currentFunction ?: return transformed
            val result = transformGreaterThan(transformed, fn)
            if (ENABLE_DEBUG_LOGGING) {
                java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText(
                    "Transformed GT comparison at ${transformed.startOffset}\n"
                )
            }
            return result
        }

        return transformed
    }

    /**
     * Transforms a > (greater than) comparison into a mutation-aware when expression.
     *
     * Original: `a > b`
     *
     * Transformed:
     * ```
     * when (MutationRegistry.check(pointId, 3)) {
     *     0 -> a >= b
     *     1 -> a < b
     *     2 -> a == b
     *     else -> a > b
     * }
     * ```
     */
    private fun transformGreaterThan(original: IrCall, containingFunction: IrSimpleFunction): IrExpression {
        val checkFn = checkFunction
        val registryClass = mutationRegistryClass

        if (checkFn == null) {
            java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText("checkFunction is NULL!\n")
            return original
        }
        if (registryClass == null) {
            java.io.File("/tmp/mutflow-plugin-invoked.txt").appendText("mutationRegistryClass is NULL!\n")
            return original
        }

        // Get the two arguments of the comparison
        val left = original.arguments[0] ?: return original
        val right = original.arguments[1] ?: return original

        val pointId = generatePointId()
        val variantCount = 3
        val sourceLocation = getSourceLocation(original)
        val originalOperator = ">"
        val variantOperators = ">=,<,=="  // comma-separated

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)

        return builder.irBlock(resultType = pluginContext.irBuiltIns.booleanType) {
            // Create temp variable for check result
            // In K2 IR for member functions:
            // - arguments[0] is the dispatch receiver (for object members)
            // - arguments[1+] are the value parameters
            val checkCall = irCall(checkFn).also { call ->
                call.arguments[0] = irGetObject(registryClass)  // dispatch receiver
                call.arguments[1] = irString(pointId)           // pointId: String
                call.arguments[2] = irInt(variantCount)         // variantCount: Int
                call.arguments[3] = irString(sourceLocation)    // sourceLocation: String
                call.arguments[4] = irString(originalOperator)  // originalOperator: String
                call.arguments[5] = irString(variantOperators)  // variantOperators: String
            }
            val checkResult = irTemporary(checkCall, nameHint = "mutationCheck")

            // Build when expression
            +IrWhenImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                type = pluginContext.irBuiltIns.booleanType,
                origin = null
            ).apply {
                // Variant 0: >= (greater or equal)
                branches += IrBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = irEquals(irGet(checkResult), irInt(0)),
                    result = createComparisonWithBuiltins(
                        left.deepCopyWithSymbols(),
                        right.deepCopyWithSymbols(),
                        ComparisonType.GREATER_OR_EQUAL
                    )
                )
                // Variant 1: < (less)
                branches += IrBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = irEquals(irGet(checkResult), irInt(1)),
                    result = createComparisonWithBuiltins(
                        left.deepCopyWithSymbols(),
                        right.deepCopyWithSymbols(),
                        ComparisonType.LESS
                    )
                )
                // Variant 2: == (equals)
                branches += IrBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = irEquals(irGet(checkResult), irInt(2)),
                    result = createComparisonWithBuiltins(
                        left.deepCopyWithSymbols(),
                        right.deepCopyWithSymbols(),
                        ComparisonType.EQUALS
                    )
                )
                // Else: original (>)
                branches += IrElseBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = irTrue(),
                    result = original.deepCopyWithSymbols()
                )
            }
        }
    }

    private enum class ComparisonType {
        GREATER_OR_EQUAL, LESS, EQUALS
    }

    private fun IrBuilderWithScope.createComparisonWithBuiltins(
        left: IrExpression,
        right: IrExpression,
        type: ComparisonType
    ): IrExpression {
        // Use the irBuiltIns comparison functions
        return when (type) {
            ComparisonType.GREATER_OR_EQUAL -> {
                // a >= b is equivalent to !(a < b)
                // Or we can use the greaterOrEqual intrinsic if available
                irCall(pluginContext.irBuiltIns.greaterOrEqualFunByOperandType[pluginContext.irBuiltIns.intClass]!!).also {
                    it.arguments[0] = left
                    it.arguments[1] = right
                }
            }
            ComparisonType.LESS -> {
                irCall(pluginContext.irBuiltIns.lessFunByOperandType[pluginContext.irBuiltIns.intClass]!!).also {
                    it.arguments[0] = left
                    it.arguments[1] = right
                }
            }
            ComparisonType.EQUALS -> {
                irCall(pluginContext.irBuiltIns.eqeqSymbol).also {
                    it.arguments[0] = left
                    it.arguments[1] = right
                }
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

    companion object {
        private const val ENABLE_DEBUG_LOGGING = false
    }
}
