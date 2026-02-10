package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrElseBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
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
    private val callOperators: List<MutationOperator> = defaultCallOperators(),
    private val returnOperators: List<ReturnMutationOperator> = defaultReturnOperators(),
    private val functionBodyOperators: List<FunctionBodyMutationOperator> = defaultFunctionBodyOperators()
) : IrElementTransformerVoid() {

    companion object {
        private const val ENABLE_DEBUG_LOGGING = false

        private fun debug(msg: String) {
            if (ENABLE_DEBUG_LOGGING) {
                // Use System.err which reliably shows in build output
                System.err.println("[MUTFLOW] $msg")
                // Also try to write to a log file in user home (more reliable than /tmp)
                try {
                    val logFile = java.io.File(System.getProperty("user.home"), "mutflow-debug.log")
                    logFile.appendText("[MUTFLOW] $msg\n")
                } catch (_: Exception) {
                    // Ignore file write errors
                }
            }
        }

        fun defaultCallOperators(): List<MutationOperator> = listOf(
            RelationalComparisonOperator(),
            ConstantBoundaryOperator(),
            ArithmeticOperator(),
            EqualitySwapOperator()
        )

        fun defaultReturnOperators(): List<ReturnMutationOperator> = listOf(
            BooleanReturnOperator(),
            NullableReturnOperator()
        )

        fun defaultFunctionBodyOperators(): List<FunctionBodyMutationOperator> = listOf(
            VoidFunctionBodyOperator()
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

    // Tracks how many times the same (lineNumber, originalOperator) pair has been seen
    // within the current class, so we can disambiguate display names.
    // Key: "lineNumber:originalOperator", Value: current count (1-based)
    private val lineOperatorOccurrences = mutableMapOf<String, Int>()

    // Comment-based line suppression (mutflow:ignore / mutflow:falsePositive)
    private var suppressedLines: Set<Int> = emptySet()
    private val suppressedLinesCache = mutableMapOf<String, Set<Int>>()

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
        val previousSuppressedLines = suppressedLines
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
            lineOperatorOccurrences.clear()
            // Parse source file for comment-based line suppression
            val filePath = currentFile?.fileEntry?.name
            suppressedLines = if (filePath != null) parseSuppressedLines(filePath) else emptySet()
            debug("  -> WILL TRANSFORM this class!")
        }

        val result = super.visitClass(declaration)

        isInMutationTarget = wasMutationTarget
        isInSuppressedScope = wasSuppressed
        suppressedLines = previousSuppressedLines
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

        // Apply function body operators (e.g., void function body removal).
        // This runs after child transformations so inner expressions are already mutated.
        if (isInMutationTarget && !isInSuppressedScope && !isLineSuppressedByComment(declaration.startOffset)) {
            transformFunctionBody(declaration)
        }

        // After all transformations, fix parent pointers for all declarations.
        // deepCopyWithSymbols() and IR tree restructuring (wrapping expressions
        // in when blocks) can leave declaration parents unset - particularly for
        // lambda function declarations within the transformed tree.
        if (isInMutationTarget && !isInSuppressedScope) {
            declaration.body?.patchDeclarationParents(declaration)
        }

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
        if (isLineSuppressedByComment(transformed.startOffset)) {
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
        if (isLineSuppressedByComment(transformed.startOffset)) {
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
            // Use original directly - no deep copy needed.
            // The original is no longer at its old tree position (replaced by the when block),
            // so it's safe to place it in the else branch. Avoiding deepCopyWithSymbols()
            // prevents creating copies of lambda declarations with unset parent pointers.
            return original
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
     * Generates a when expression with inline check() calls (no temporary variable):
     * ```
     * when {
     *     MutationRegistry.check(...) == 0 -> variant0
     *     MutationRegistry.check(...) == 1 -> variant1
     *     ...
     *     else -> <recursively apply remaining operators>
     * }
     * ```
     *
     * check() is idempotent for the same pointId, so calling it per branch is safe.
     * This avoids creating temporary variables that can have their parent references
     * invalidated by other compiler plugins (e.g., kotlin-allopen/Spring plugin).
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
            return transformCallWithOperators(original, containingFunction, remainingOperators)
        }

        val pointId = generatePointId()
        val variantCount = variants.size
        val sourceLocation = getSourceLocation(original)
        val originalOperator = operator.originalDescription(original)
        val variantOperators = variants.joinToString(",") { it.description }
        val lineNumber = currentFile?.fileEntry?.getLineNumber(original.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, originalOperator)

        debug("MUTATION: $originalOperator at $sourceLocation (occurrence #$occurrenceOnLine) -> variants: $variantOperators")

        // Helper to create a fresh check() call for each branch condition
        fun createCheckCall() = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(variantCount)
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(originalOperator)
            call.arguments[5] = builder.irString(variantOperators)
            call.arguments[6] = builder.irInt(occurrenceOnLine)
        }

        // Generate when expression with inline check() calls - no temporary variable
        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = pluginContext.irBuiltIns.booleanType,
            origin = null
        ).apply {
            variants.forEachIndexed { index, variant ->
                branches += IrBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = builder.irEquals(createCheckCall(), builder.irInt(index)),
                    result = variant.createExpression()
                )
            }
            branches += IrElseBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irTrue(),
                result = transformCallWithOperators(original, containingFunction, remainingOperators)
            )
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
     * Generates a return with a when expression using inline check() calls:
     * ```
     * return when {
     *     MutationRegistry.check(...) == 0 -> true
     *     MutationRegistry.check(...) == 1 -> false
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
        val lineNumber = currentFile?.fileEntry?.getLineNumber(original.value.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, originalDescription)

        val fnName = containingFunction.name.asString()
        debug("MUTATION: RETURN in $fnName at $sourceLocation (occurrence #$occurrenceOnLine) -> variants: $variantDescriptions")

        val originalValue = original.value

        // Use the function's return type for the when type
        val blockType = containingFunction.returnType

        // Helper to create a fresh check() call for each branch condition
        fun createCheckCall() = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(variantCount)
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(originalDescription)
            call.arguments[5] = builder.irString(variantDescriptions)
            call.arguments[6] = builder.irInt(occurrenceOnLine)
        }

        // Generate when expression with inline check() calls - no temporary variable
        val newValue = IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = blockType,
            origin = null
        ).apply {
            variants.forEachIndexed { index, variant ->
                branches += IrBranchImpl(
                    startOffset = original.startOffset,
                    endOffset = original.endOffset,
                    condition = builder.irEquals(createCheckCall(), builder.irInt(index)),
                    result = variant.createExpression()
                )
            }
            branches += IrElseBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irTrue(),
                // Use original value directly - no deep copy needed.
                // The original return is replaced by a new IrReturnImpl, so originalValue
                // is no longer at its old position. Avoiding deepCopyWithSymbols()
                // prevents creating copies of lambda declarations with unset parent pointers.
                result = originalValue
            )
        }

        return IrReturnImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = original.type,
            returnTargetSymbol = original.returnTargetSymbol,
            value = newValue
        )
    }

    /**
     * Applies matching function body operators to a function declaration.
     *
     * Wraps the function body in a when expression that either skips the body
     * entirely (mutation active) or executes normally (else branch):
     * ```
     * fun save(entity: Entity) {
     *     when {
     *         MutationRegistry.check(...) == 0 -> { }  // empty — skip body
     *         else -> { original statements }
     *     }
     * }
     * ```
     */
    private fun transformFunctionBody(declaration: IrSimpleFunction) {
        val operator = functionBodyOperators.firstOrNull { it.matches(declaration) } ?: return

        val checkFn = checkFunction ?: return
        val registryClass = mutationRegistryClass ?: return
        val body = declaration.body as? IrBlockBody ?: return

        val builder = DeclarationIrBuilder(pluginContext, declaration.symbol)

        val pointId = generatePointId()
        val variantCount = operator.variantCount(declaration)
        val sourceLocation = getFunctionSourceLocation(declaration)
        val originalDescription = operator.originalDescription(declaration)
        val variantDescriptions = operator.variantDescriptions(declaration).joinToString(",")
        val lineNumber = currentFile?.fileEntry?.getLineNumber(declaration.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, originalDescription)

        debug("MUTATION: BODY of $originalDescription at $sourceLocation (occurrence #$occurrenceOnLine) -> variants: $variantDescriptions")

        fun createCheckCall() = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(variantCount)
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(originalDescription)
            call.arguments[5] = builder.irString(variantDescriptions)
            call.arguments[6] = builder.irInt(occurrenceOnLine)
        }

        val unitType = pluginContext.irBuiltIns.unitType

        // Move original statements into a block expression for the else branch
        val originalStatements = body.statements.toList()
        val originalBlock = IrBlockImpl(
            startOffset = declaration.startOffset,
            endOffset = declaration.endOffset,
            type = unitType,
            origin = null
        ).apply {
            statements.addAll(originalStatements)
        }

        // Build the when expression
        val whenExpr = IrWhenImpl(
            startOffset = declaration.startOffset,
            endOffset = declaration.endOffset,
            type = unitType,
            origin = null
        ).apply {
            // Mutation branch: empty block (skip body)
            for (index in 0 until variantCount) {
                branches += IrBranchImpl(
                    startOffset = declaration.startOffset,
                    endOffset = declaration.endOffset,
                    condition = builder.irEquals(createCheckCall(), builder.irInt(index)),
                    result = IrBlockImpl(
                        startOffset = declaration.startOffset,
                        endOffset = declaration.endOffset,
                        type = unitType,
                        origin = null
                    )
                )
            }
            // Else branch: original body
            branches += IrElseBranchImpl(
                startOffset = declaration.startOffset,
                endOffset = declaration.endOffset,
                condition = builder.irTrue(),
                result = originalBlock
            )
        }

        // Replace body statements with the single when expression
        body.statements.clear()
        body.statements.add(whenExpr)
    }

    /**
     * Extracts source location from a function declaration.
     */
    private fun getFunctionSourceLocation(function: IrSimpleFunction): String {
        val file = currentFile ?: return "unknown:0"
        val fileName = file.fileEntry.name.substringAfterLast('/')
        val lineNumber = file.fileEntry.getLineNumber(function.startOffset) + 1
        return "$fileName:$lineNumber"
    }

    private fun generatePointId(): String {
        val className = currentClass?.fqNameWhenAvailable?.asString() ?: "unknown"
        return "${className}_${mutationPointCounter++}"
    }

    /**
     * Returns the 1-based occurrence index for this (line, operator) combination
     * and increments the counter. First occurrence returns 1, second returns 2, etc.
     */
    private fun nextOccurrenceOnLine(lineNumber: Int, originalOperator: String): Int {
        val key = "$lineNumber:$originalOperator"
        val occurrence = (lineOperatorOccurrences[key] ?: 0) + 1
        lineOperatorOccurrences[key] = occurrence
        return occurrence
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

    /**
     * Checks if the given IR offset falls on a line suppressed by a comment
     * (mutflow:ignore or mutflow:falsePositive).
     */
    private fun isLineSuppressedByComment(startOffset: Int): Boolean {
        if (suppressedLines.isEmpty()) return false
        val file = currentFile ?: return false
        val lineNumber = file.fileEntry.getLineNumber(startOffset) + 1 // 1-based
        return lineNumber in suppressedLines
    }

    /**
     * Reads a source file and parses lines containing mutflow:ignore or mutflow:falsePositive
     * comments. Returns a set of 1-based line numbers that should be suppressed.
     *
     * Supports two styles:
     * - Inline: `if (a > b) { // mutflow:ignore reason` → suppresses this line
     * - Standalone: `// mutflow:ignore reason\nif (a > b)` → suppresses the next line
     *
     * Results are cached per file path.
     */
    private fun parseSuppressedLines(filePath: String): Set<Int> {
        suppressedLinesCache[filePath]?.let { return it }

        val lines = try {
            java.io.File(filePath).readLines()
        } catch (e: Exception) {
            System.err.println(
                "[mutflow] WARNING: Could not read source file $filePath — " +
                    "comment-based suppression (mutflow:ignore / mutflow:falsePositive) " +
                    "unavailable for this file"
            )
            suppressedLinesCache[filePath] = emptySet()
            return emptySet()
        }

        val suppressed = mutableSetOf<Int>()

        for ((index, line) in lines.withIndex()) {
            val lineNumber = index + 1 // 1-based
            val commentStart = line.indexOf("//")
            if (commentStart < 0) continue

            val commentText = line.substring(commentStart + 2)
            if (!commentText.contains("mutflow:ignore") && !commentText.contains("mutflow:falsePositive")) {
                continue
            }

            if (line.trimStart().startsWith("//")) {
                // Standalone comment line → suppress the next line
                suppressed.add(lineNumber + 1)
            } else {
                // Inline comment → suppress this line
                suppressed.add(lineNumber)
            }
        }

        debug("Parsed suppressed lines for $filePath: $suppressed")
        suppressedLinesCache[filePath] = suppressed
        return suppressed
    }
}
