package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrWhenImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
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
 * Uses an extensible MutationOperator mechanism to support different
 * categories of mutations (comparisons, arithmetic, etc.).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class MutflowIrTransformer(
    private val pluginContext: IrPluginContext,
    private val callOperators: List<MutationOperator> = defaultCallOperators(),
    private val returnOperators: List<ReturnMutationOperator> = defaultReturnOperators()
) : IrElementTransformerVoid() {

    companion object {
        private const val ENABLE_DEBUG_LOGGING = true

        private fun debug(msg: String) {
            if (ENABLE_DEBUG_LOGGING) {
                val logFile = java.io.File("/tmp/mutflow-debug.log")
                logFile.appendText("[MUTFLOW-TRANSFORMER] $msg\n")
                println("[MUTFLOW-TRANSFORMER] $msg")
            }
        }

        fun defaultCallOperators(): List<MutationOperator> = listOf(
            RelationalComparisonOperator(),
            ConstantBoundaryOperator(),
            ArithmeticOperator()
        )

        fun defaultReturnOperators(): List<ReturnMutationOperator> = listOf(
            BooleanReturnOperator(),
            NullableReturnOperator()
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
        debug("visitFile: ${declaration.fileEntry.name}")
        val previousFile = currentFile
        currentFile = declaration
        val result = super.visitFile(declaration)
        currentFile = previousFile
        return result
    }

    override fun visitClass(declaration: IrClass): IrStatement {
        debug("visitClass: ${declaration.fqNameWhenAvailable}")
        debug("  annotations count: ${declaration.annotations.size}")

        val wasMutationTarget = isInMutationTarget
        val wasSuppressed = isInSuppressedScope
        val previousClass = currentClass

        isInMutationTarget = declaration.hasAnnotation(mutationTargetFqName)
        currentClass = declaration

        debug("  hasAnnotation($mutationTargetFqName): $isInMutationTarget")

        // Check for @SuppressMutations on the class
        if (isInMutationTarget && declaration.hasAnnotation(suppressMutationsFqName)) {
            isInSuppressedScope = true
        }

        if (isInMutationTarget && !isInSuppressedScope) {
            mutationPointCounter = 0
            debug("  -> WILL TRANSFORM this class!")
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
        return transformCallWithOperators(transformed, fn, callOperators)
    }

    override fun visitReturn(expression: IrReturn): IrExpression {
        // First, transform the return value (bottom-up for nested expressions)
        val transformed = super.visitReturn(expression) as IrReturn

        // Only transform if we're in a @MutationTarget class and not suppressed
        if (!isInMutationTarget || isInSuppressedScope) {
            return transformed
        }

        val fn = currentFunction ?: return transformed
        return transformReturnWithOperators(transformed, fn, returnOperators)
    }

    /**
     * Recursively applies matching call operators to an expression.
     *
     * Each matching operator wraps the expression in a when block, with
     * the else branch passing to the next operator. This enables multiple
     * independent mutation points on the same expression (e.g., operator
     * mutation AND constant boundary mutation).
     */
    private fun transformCallWithOperators(
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
            return transformCallWithOperators(original, containingFunction, rest)
        }

        return transformCallWithOperator(original, containingFunction, operator, rest)
    }

    /**
     * Transforms a call expression using the given mutation operator.
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
    private fun transformCallWithOperator(
        original: IrCall,
        containingFunction: IrSimpleFunction,
        operator: MutationOperator,
        remainingOperators: List<MutationOperator>
    ): IrExpression {
        val checkFn = checkFunction ?: run {
            debug("ERROR: checkFunction is NULL! MutationRegistry.check not found on classpath")
            return original
        }
        val registryClass = mutationRegistryClass ?: run {
            debug("ERROR: mutationRegistryClass is NULL! MutationRegistry not found on classpath")
            return original
        }

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
        val context = MutationContext(pluginContext, builder, containingFunction)

        val variants = operator.variants(original, context)
        if (variants.isEmpty()) {
            // No variants from this operator, try remaining operators
            return transformCallWithOperators(original, containingFunction, remainingOperators)
        }

        val pointId = generatePointId()
        val variantCount = variants.size
        val sourceLocation = getSourceLocation(original)
        val originalOperator = operator.originalDescription(original)
        val variantOperators = variants.joinToString(",") { it.description }

        debug("MUTATION: $originalOperator at $sourceLocation -> variants: $variantOperators")

        // Build IR manually to ensure proper parent setting
        // Create the check call
        val checkCall = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)  // dispatch receiver
            call.arguments[1] = builder.irString(pointId)           // pointId: String
            call.arguments[2] = builder.irInt(variantCount)         // variantCount: Int
            call.arguments[3] = builder.irString(sourceLocation)    // sourceLocation: String
            call.arguments[4] = builder.irString(originalOperator)  // originalOperator: String
            call.arguments[5] = builder.irString(variantOperators)  // variantOperators: String
        }

        // Create temporary variable manually with parent set explicitly
        val checkResultVar = IrVariableImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            origin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
            symbol = IrVariableSymbolImpl(),
            name = Name.identifier("mutationCheck"),
            type = pluginContext.irBuiltIns.intType.makeNullable(),
            isVar = false,
            isConst = false,
            isLateinit = false
        )
        // Set parent and initializer AFTER construction (not in apply block)
        checkResultVar.parent = containingFunction
        checkResultVar.initializer = checkCall
        debug("  Created checkResultVar, parent set to: ${(checkResultVar.parent as? IrSimpleFunction)?.name ?: checkResultVar.parent}")

        // Create the when expression
        val whenExpr = IrWhenImpl(
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
                    condition = builder.irEquals(
                        IrGetValueImpl(
                            startOffset = original.startOffset,
                            endOffset = original.endOffset,
                            type = checkResultVar.type,
                            symbol = checkResultVar.symbol,
                            origin = null
                        ),
                        builder.irInt(index)
                    ),
                    result = variant.createExpression()
                )
            }
            // Else: recursively apply remaining operators (or original if none left)
            branches += IrElseBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irTrue(),
                result = transformCallWithOperators(original, containingFunction, remainingOperators)
            )
        }

        // Create block containing variable declaration and when expression
        return IrBlockImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = pluginContext.irBuiltIns.booleanType,
            origin = null
        ).apply {
            statements += checkResultVar
            statements += whenExpr
        }.also {
            // Patch any nested declarations (from variant expressions)
            it.patchDeclarationParents(containingFunction)
        }
    }

    /**
     * Applies matching return operators to a return statement.
     *
     * Unlike call operators which can nest, return operators replace the
     * return value directly. Only the first matching operator is applied.
     */
    private fun transformReturnWithOperators(
        original: IrReturn,
        containingFunction: IrSimpleFunction,
        remainingOperators: List<ReturnMutationOperator>
    ): IrExpression {
        if (remainingOperators.isEmpty()) {
            return original
        }

        val operator = remainingOperators.first()
        val rest = remainingOperators.drop(1)

        if (!operator.matches(original)) {
            return transformReturnWithOperators(original, containingFunction, rest)
        }

        return transformReturnWithOperator(original, containingFunction, operator)
    }

    /**
     * Transforms a return statement using the given mutation operator.
     *
     * Generates a return with a when expression as its value:
     * ```
     * return when (MutationRegistry.check(pointId, variantCount, ...)) {
     *     0 -> true
     *     1 -> false
     *     else -> <original expression>
     * }
     * ```
     */
    private fun transformReturnWithOperator(
        original: IrReturn,
        containingFunction: IrSimpleFunction,
        operator: ReturnMutationOperator
    ): IrExpression {
        val checkFn = checkFunction ?: return original
        val registryClass = mutationRegistryClass ?: return original

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
        val context = MutationContext(pluginContext, builder, containingFunction)

        val variants = operator.variants(original, context)
        if (variants.isEmpty()) {
            return original
        }

        val pointId = generatePointId()
        val variantCount = variants.size
        val sourceLocation = getSourceLocation(original.value)
        val originalDescription = operator.originalDescription(original)
        val variantDescriptions = variants.joinToString(",") { it.description }

        val fnName = containingFunction.name.asString()
        debug("MUTATION: RETURN in $fnName at $sourceLocation -> variants: $variantDescriptions")

        val originalValue = original.value

        // Use the function's return type for the block/when type.
        // This is important when the original value type differs from the function return type
        // (e.g., returning non-null Int from a function that returns Int?)
        val blockType = containingFunction.returnType

        // Build IR manually to ensure proper parent setting
        val checkCall = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(variantCount)
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(originalDescription)
            call.arguments[5] = builder.irString(variantDescriptions)
        }

        // Create temporary variable manually with parent set explicitly
        val checkResultVar = IrVariableImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            origin = IrDeclarationOrigin.IR_TEMPORARY_VARIABLE,
            symbol = IrVariableSymbolImpl(),
            name = Name.identifier("mutationCheck"),
            type = pluginContext.irBuiltIns.intType.makeNullable(),
            isVar = false,
            isConst = false,
            isLateinit = false
        )
        // Set parent and initializer AFTER construction (not in apply block)
        checkResultVar.parent = containingFunction
        checkResultVar.initializer = checkCall
        debug("  Created checkResultVar, parent set to: ${(checkResultVar.parent as? IrSimpleFunction)?.name ?: checkResultVar.parent}")

        // Create the when expression
        val whenExpr = IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = blockType,
            origin = null
        ).apply {
            variants.forEachIndexed { index, variant ->
                branches += IrBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = builder.irEquals(
                        IrGetValueImpl(
                            startOffset = original.startOffset,
                            endOffset = original.endOffset,
                            type = checkResultVar.type,
                            symbol = checkResultVar.symbol,
                            origin = null
                        ),
                        builder.irInt(index)
                    ),
                    result = variant.createExpression()
                )
            }
            branches += IrElseBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irTrue(),
                result = originalValue.deepCopyWithSymbols()
            )
        }

        // Create block containing variable declaration and when expression
        val newValue = IrBlockImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = blockType,
            origin = null
        ).apply {
            statements += checkResultVar
            statements += whenExpr
        }

        // Create a new IrReturn with the mutated value, preserving the original's return target
        return IrReturnImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = original.type,
            returnTargetSymbol = original.returnTargetSymbol,
            value = newValue
        ).also {
            // Patch any nested declarations (from variant expressions)
            it.patchDeclarationParents(containingFunction)
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
