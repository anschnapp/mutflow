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
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isUnit
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
    private val functionBodyOperators: List<FunctionBodyMutationOperator> = defaultFunctionBodyOperators(),
    private val whenOperators: List<WhenMutationOperator> = defaultWhenOperators(),
    private val constOperators: List<ConstMutationOperator> = defaultConstOperators(),
    private val constructorCallOperators: List<ConstructorCallMutationOperator> = defaultConstructorCallOperators(),
    private val assignmentOperators: List<AssignmentMutationOperator> = defaultAssignmentOperators(),
    private val targetPatterns: List<String> = emptyList()
) : IrElementTransformerVoid() {

    companion object {
        fun defaultCallOperators(): List<MutationOperator> = MutationCatalog.callOperators

        fun defaultReturnOperators(): List<ReturnMutationOperator> = MutationCatalog.returnOperators

        fun defaultFunctionBodyOperators(): List<FunctionBodyMutationOperator> = MutationCatalog.functionBodyOperators

        fun defaultWhenOperators(): List<WhenMutationOperator> = MutationCatalog.whenOperators

        fun defaultConstOperators(): List<ConstMutationOperator> = MutationCatalog.constOperators

        fun defaultConstructorCallOperators(): List<ConstructorCallMutationOperator> =
            MutationCatalog.constructorCallOperators

        fun defaultAssignmentOperators(): List<AssignmentMutationOperator> =
            MutationCatalog.assignmentOperators

        /**
         * Compiles glob-style target patterns into regexes for FQN matching.
         * - `.` matches literal dots
         * - `*` matches a single name segment (does not cross dots)
         * - `**` matches any number of segments (crosses dots)
         */
        fun compileTargetPatterns(patterns: List<String>): List<Regex> = patterns.map { pattern ->
            val regex = pattern
                .replace(".", "\\.")       // literal dots
                .replace("**", "\u0000")   // temp placeholder for **
                .replace("*", "[^.]*")     // * matches single package segment
                .replace("\u0000", ".*")   // ** matches any depth
            Regex("^$regex$")
        }
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

    private val checkTimeoutFunction: IrSimpleFunctionSymbol? by lazy {
        val classId = ClassId.topLevel(mutationRegistryFqName)
        pluginContext.referenceFunctions(
            CallableId(classId, Name.identifier("checkTimeout"))
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

    /**
     * Boolean constants that form the structural shape of `&&`/`||` whens.
     *
     * The JVM backend asserts the exact shape of these whens (e.g. `&&` must end
     * with an `else -> true -> false` pair), so mutating those constants would
     * break lowering. The `&&` ↔ `||` swap is already handled by
     * [BooleanLogicOperator], so skipping them loses no coverage.
     */
    private var structuralBooleanConsts: Set<IrConst> = emptySet()

    // Compiled target patterns from Gradle config (glob-style → regex)
    private val compiledTargetPatterns: List<Regex> = compileTargetPatterns(targetPatterns)

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
        val previousSuppressedLines = suppressedLines
        val previousClass = currentClass

        isInMutationTarget = declaration.hasAnnotation(mutationTargetFqName)
                || matchesTargetPattern(declaration)
        currentClass = declaration

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
        val result = transformCallWithOperators(transformed, fn, callOperators)
        return result
    }

    override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
        // First, transform children (bottom-up for nested expressions)
        val transformed = super.visitConstructorCall(expression) as IrConstructorCall

        // Only transform if we're in a @MutationTarget class and not suppressed
        if (!isInMutationTarget || isInSuppressedScope) {
            return transformed
        }
        if (isLineSuppressedByComment(transformed.startOffset)) {
            return transformed
        }

        val fn = currentFunction ?: return transformed
        return transformConstructorCallWithOperators(transformed, fn, constructorCallOperators)
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        // First, transform children (bottom-up for nested expressions)
        val transformed = super.visitSetValue(expression) as IrSetValue

        // Only transform if we're in a @MutationTarget class and not suppressed
        if (!isInMutationTarget || isInSuppressedScope) {
            return transformed
        }
        if (isLineSuppressedByComment(transformed.startOffset)) {
            return transformed
        }

        val fn = currentFunction ?: return transformed
        val mutatedValue = transformAssignmentValue(
            transformed.value, transformed.symbol.owner.type, transformed, fn
        )
        if (mutatedValue === transformed.value) return transformed
        // Rebuild the assignment with the mutation-guarded value.
        transformed.value = mutatedValue
        return transformed
    }

    override fun visitSetField(expression: IrSetField): IrExpression {
        // First, transform children (bottom-up for nested expressions)
        val transformed = super.visitSetField(expression) as IrSetField

        if (!isInMutationTarget || isInSuppressedScope) {
            return transformed
        }
        if (isLineSuppressedByComment(transformed.startOffset)) {
            return transformed
        }

        val fn = currentFunction ?: return transformed
        val mutatedValue = transformAssignmentValue(
            transformed.value, transformed.symbol.owner.type, transformed, fn
        )
        if (mutatedValue === transformed.value) return transformed
        // Rebuild the assignment with the mutation-guarded value.
        transformed.value = mutatedValue
        return transformed
    }

    override fun visitGetValue(expression: IrGetValue): IrExpression {
        val transformed = super.visitGetValue(expression) as IrGetValue

        if (!isInMutationTarget || isInSuppressedScope) return transformed
        if (!transformed.type.isBoolean()) return transformed
        if (isLineSuppressedByComment(transformed.startOffset)) return transformed

        val fn = currentFunction ?: return transformed
        return transformBooleanGetValue(transformed, fn)
    }

    /**
     * Transforms a boolean IrGetValue (variable/parameter read) into a mutation point.
     *
     * Generates:
     * ```
     * when {
     *     MutationRegistry.check(...) == 0 -> !varName
     *     else -> varName
     * }
     * ```
     *
     * IrGetValue is a leaf node so no recursion is needed.
     */
    private fun transformBooleanGetValue(
        original: IrGetValue,
        containingFunction: IrSimpleFunction
    ): IrExpression {
        val checkFn = checkFunction ?: return original
        val registryClass = mutationRegistryClass ?: return original

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)

        val varName = original.symbol.owner.name.asString()
        val pointId = generatePointId()
        val sourceLocation = getSourceLocation(original)
        val lineNumber = currentFile?.fileEntry?.getLineNumber(original.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, varName)

        fun createCheckCall() = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(1) // single variant
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(varName)
            call.arguments[5] = builder.irString("!$varName")
            call.arguments[6] = builder.irInt(occurrenceOnLine)
        }

        val booleanNotSymbol = pluginContext.irBuiltIns.booleanNotSymbol

        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = pluginContext.irBuiltIns.booleanType,
            origin = null
        ).apply {
            branches += IrBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irEquals(createCheckCall(), builder.irInt(0)),
                result = builder.irCall(booleanNotSymbol).also {
                    it.dispatchReceiver = original.deepCopyWithSymbols()
                }
            )
            branches += IrElseBranchImpl(
                startOffset = original.startOffset,
                endOffset = original.endOffset,
                condition = builder.irTrue(),
                result = original
            )
        }
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

    override fun visitBlock(expression: IrBlock): IrExpression {
        // Record the enclosing block's origin so when-operators (elvis, safe-call)
        // can identify constructs whose distinguishing origin sits on this block
        // rather than on the inner IrWhen (common-IR KMP form).
        val previousOrigin = EnclosingOriginProvider.currentOrigin
        EnclosingOriginProvider.currentOrigin = expression.origin?.debugName
        try {
            return super.visitBlock(expression)
        } finally {
            EnclosingOriginProvider.currentOrigin = previousOrigin
        }
    }

    override fun visitWhen(expression: IrWhen): IrExpression {
        // Record the structural boolean constants of &&/|| whens so const
        // operators don't mutate them (the JVM backend asserts their shape).
        val previousStructural = structuralBooleanConsts
        structuralBooleanConsts = collectStructuralBooleanConsts(expression)
        // First, transform children (bottom-up for nested expressions)
        val transformed = super.visitWhen(expression) as IrWhen
        structuralBooleanConsts = previousStructural

        // Only transform if we're in a @MutationTarget class and not suppressed
        if (!isInMutationTarget || isInSuppressedScope) {
            return transformed
        }
        if (isLineSuppressedByComment(transformed.startOffset)) {
            return transformed
        }

        val fn = currentFunction ?: return transformed
        val result = transformWhenWithOperators(transformed, fn, whenOperators)
        return result
    }

    /**
     * Returns the boolean constants that the JVM backend asserts on for `&&`/`||`
     * whens: ANDAND requires `branches[1].condition == true` and
     * `branches[1].result == false`; OROR requires `branches[0].result == true`
     * and `branches[1].condition == true`.
     */
    private fun collectStructuralBooleanConsts(whenExpr: IrWhen): Set<IrConst> {
        val structural = mutableSetOf<IrConst>()
        when (whenExpr.origin) {
            IrStatementOrigin.ANDAND -> {
                if (whenExpr.branches.size == 2) {
                    (whenExpr.branches[1].condition as? IrConst)?.let { structural += it }
                    (whenExpr.branches[1].result as? IrConst)?.let { structural += it }
                }
            }
            IrStatementOrigin.OROR -> {
                if (whenExpr.branches.size == 2) {
                    (whenExpr.branches[0].result as? IrConst)?.let { structural += it }
                    (whenExpr.branches[1].condition as? IrConst)?.let { structural += it }
                }
            }
            else -> {}
        }
        // Protect the else-branch `true` const of any when whose last branch is a
        // plain branch (not IrElseBranch) with a `true` condition. K2 represents
        // `if/else` this way, and the JS backend's isElseBranch() relies on that
        // const to recognize the implicit else. Mutating it (e.g. BooleanConstOperator
        // wrapping it in a schemata when) breaks the JS backend's
        // "Non unit when-expression must have else branch" assertion.
        val last = whenExpr.branches.lastOrNull()
        if (last != null) {
            val cond = last.condition
            if (cond is IrConst && cond.value == true) {
                structural += cond
            }
        }
        return structural
    }

    override fun visitConst(expression: IrConst): IrExpression {
        // Constants are leaf nodes, so no child transformation is needed.
        if (!isInMutationTarget || isInSuppressedScope) {
            return expression
        }
        if (isLineSuppressedByComment(expression.startOffset)) {
            return expression
        }
        // Skip structural boolean constants of &&/|| whens (JVM backend asserts their shape).
        if (expression in structuralBooleanConsts) {
            return expression
        }

        val fn = currentFunction ?: return expression
        val result = transformConstWithOperators(expression, fn, constOperators)
        return result
    }

    override fun visitWhileLoop(loop: IrWhileLoop): IrExpression {
        val result = super.visitWhileLoop(loop) as IrWhileLoop
        if (!isInMutationTarget || isInSuppressedScope) return result
        injectTimeoutCheck(result)
        return result
    }

    override fun visitDoWhileLoop(loop: IrDoWhileLoop): IrExpression {
        val result = super.visitDoWhileLoop(loop) as IrDoWhileLoop
        if (!isInMutationTarget || isInSuppressedScope) return result
        injectTimeoutCheck(result)
        return result
    }

    /**
     * Injects a MutationRegistry.checkTimeout() call at the top of a loop body.
     * This prevents mutations that cause infinite loops from hanging the test run.
     */
    private fun injectTimeoutCheck(loop: IrLoop) {
        val body = loop.body ?: return
        val checkTimeoutFn = checkTimeoutFunction ?: return
        val registryClass = mutationRegistryClass ?: return
        val fn = currentFunction ?: return

        val builder = DeclarationIrBuilder(pluginContext, fn.symbol)
        val checkCall = builder.irCall(checkTimeoutFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
        }

        loop.body = IrBlockImpl(
            startOffset = body.startOffset,
            endOffset = body.endOffset,
            type = pluginContext.irBuiltIns.unitType,
            origin = null
        ).apply {
            statements.add(checkCall)
            statements.add(body)
        }
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
            return original
        }
        val registryClass = mutationRegistryClass ?: run {
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

        // Generate when expression with inline check() calls - no temporary variable.
        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = original.type,
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
     * Recursively applies matching constructor-call operators to an expression.
     *
     * Mirrors [transformCallWithOperators] for [IrConstructorCall], which is a
     * distinct IR node type from [IrCall] in Kotlin 2.4.0.
     */
    private fun transformConstructorCallWithOperators(
        original: IrConstructorCall,
        containingFunction: IrSimpleFunction,
        remainingOperators: List<ConstructorCallMutationOperator>
    ): IrExpression {
        if (remainingOperators.isEmpty()) {
            return original
        }

        val operator = remainingOperators.first()
        val rest = remainingOperators.drop(1)

        if (!operator.matches(original)) {
            return transformConstructorCallWithOperators(original, containingFunction, rest)
        }

        return transformConstructorCallWithOperator(original, containingFunction, operator, rest)
    }

    /**
     * Transforms a constructor call using the given mutation operator.
     *
     * Generates the same `when` + inline `check()` shape as [transformCallWithOperator].
     */
    private fun transformConstructorCallWithOperator(
        original: IrConstructorCall,
        containingFunction: IrSimpleFunction,
        operator: ConstructorCallMutationOperator,
        remainingOperators: List<ConstructorCallMutationOperator>
    ): IrExpression {
        val checkFn = checkFunction ?: run {
            return original
        }
        val registryClass = mutationRegistryClass ?: run {
            return original
        }

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
        val context = MutationContext(pluginContext, builder, containingFunction)

        val variants = operator.variants(original, context)
        if (variants.isEmpty()) {
            return transformConstructorCallWithOperators(original, containingFunction, remainingOperators)
        }

        val pointId = generatePointId()
        val variantCount = variants.size
        val sourceLocation = getSourceLocation(original)
        val originalOperator = operator.originalDescription(original)
        val variantOperators = variants.joinToString(",") { it.description }
        val lineNumber = currentFile?.fileEntry?.getLineNumber(original.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, originalOperator)

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

        // Generate when expression with inline check() calls - no temporary variable.
        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = original.type,
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
                result = transformConstructorCallWithOperators(original, containingFunction, remainingOperators)
            )
        }
    }

    /**
     * Applies matching assignment operators to an assignment's value, wrapping it
     * in a mutation-guarded `when`. Returns the mutation-guarded value expression
     * (or the original value if no operator matches). The caller assigns the result
     * back to the assignment node's `value`.
     */
    private fun transformAssignmentValue(
        assignedValue: IrExpression,
        targetType: org.jetbrains.kotlin.ir.types.IrType,
        original: IrExpression,
        containingFunction: IrSimpleFunction
    ): IrExpression {
        val checkFn = checkFunction ?: return assignedValue
        val registryClass = mutationRegistryClass ?: return assignedValue

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
        val context = MutationContext(pluginContext, builder, containingFunction)

        // Collect variants from all matching assignment operators.
        val allVariants = mutableListOf<MutationOperator.Variant>()
        val allOriginalOperators = mutableListOf<String>()
        for (operator in assignmentOperators) {
            if (operator.matches(targetType, assignedValue)) {
                val variants = operator.variants(targetType, assignedValue, context)
                if (variants.isNotEmpty()) {
                    allVariants += variants
                    allOriginalOperators += operator.originalDescription(assignedValue)
                }
            }
        }
        if (allVariants.isEmpty()) return assignedValue

        val pointId = generatePointId()
        val variantCount = allVariants.size
        val sourceLocation = getSourceLocation(original)
        val originalOperator = allOriginalOperators.firstOrNull() ?: "="
        val variantOperators = allVariants.joinToString(",") { it.description }
        val lineNumber = currentFile?.fileEntry?.getLineNumber(original.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, originalOperator)

        fun createCheckCall() = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(variantCount)
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(originalOperator)
            call.arguments[5] = builder.irString(variantOperators)
            call.arguments[6] = builder.irInt(occurrenceOnLine)
        }

        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = targetType,
            origin = null
        ).apply {
            allVariants.forEachIndexed { index, variant ->
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
                result = assignedValue
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
     * Recursively applies matching when operators to an IrWhen expression.
     *
     * Each matching operator wraps the when in a mutation check, with the
     * else branch passing to the next operator.
     */
    private fun transformWhenWithOperators(
        original: IrWhen,
        containingFunction: IrSimpleFunction,
        remainingOperators: List<WhenMutationOperator>
    ): IrExpression {
        if (remainingOperators.isEmpty()) {
            return original
        }

        val operator = remainingOperators.first()
        val rest = remainingOperators.drop(1)

        if (!operator.matches(original)) {
            return transformWhenWithOperators(original, containingFunction, rest)
        }

        return transformWhenWithOperator(original, containingFunction, operator, rest)
    }

    /**
     * Transforms a when expression using the given mutation operator.
     *
     * Generates a when expression with inline check() calls (no temporary variable):
     * ```
     * when {
     *     MutationRegistry.check(...) == 0 -> <mutated when expr>
     *     else -> <original when expr OR recursion to next operator>
     * }
     * ```
     */
    private fun transformWhenWithOperator(
        original: IrWhen,
        containingFunction: IrSimpleFunction,
        operator: WhenMutationOperator,
        remainingOperators: List<WhenMutationOperator>
    ): IrExpression {
        val checkFn = checkFunction ?: return original
        val registryClass = mutationRegistryClass ?: return original

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
        val context = MutationContext(pluginContext, builder, containingFunction)

        val variants = operator.variants(original, context)
        if (variants.isEmpty()) {
            return transformWhenWithOperators(original, containingFunction, remainingOperators)
        }

        val pointId = generatePointId()
        val variantCount = variants.size
        val sourceLocation = getSourceLocation(original)
        val originalOperator = operator.originalDescription(original)
        val variantOperators = variants.joinToString(",") { it.description }
        val lineNumber = currentFile?.fileEntry?.getLineNumber(original.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, originalOperator)

        fun createCheckCall() = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(variantCount)
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(originalOperator)
            call.arguments[5] = builder.irString(variantOperators)
            call.arguments[6] = builder.irInt(occurrenceOnLine)
        }

        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = original.type,
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
                result = transformWhenWithOperators(original, containingFunction, remainingOperators)
            )
        }
    }

    /**
     * Recursively applies matching const operators to an IrConst expression.
     *
     * Each matching operator wraps the constant in a mutation check, with the
     * else branch passing to the next operator.
     */
    private fun transformConstWithOperators(
        original: IrConst,
        containingFunction: IrSimpleFunction,
        remainingOperators: List<ConstMutationOperator>
    ): IrExpression {
        if (remainingOperators.isEmpty()) {
            return original
        }

        val operator = remainingOperators.first()
        val rest = remainingOperators.drop(1)

        if (!operator.matches(original)) {
            return transformConstWithOperators(original, containingFunction, rest)
        }

        return transformConstWithOperator(original, containingFunction, operator, rest)
    }

    /**
     * Transforms a constant expression using the given mutation operator.
     *
     * Generates a when expression with inline check() calls (no temporary variable):
     * ```
     * when {
     *     MutationRegistry.check(...) == 0 -> <mutated constant>
     *     else -> <original constant OR recursion to next operator>
     * }
     * ```
     */
    private fun transformConstWithOperator(
        original: IrConst,
        containingFunction: IrSimpleFunction,
        operator: ConstMutationOperator,
        remainingOperators: List<ConstMutationOperator>
    ): IrExpression {
        val checkFn = checkFunction ?: return original
        val registryClass = mutationRegistryClass ?: return original

        val builder = DeclarationIrBuilder(pluginContext, containingFunction.symbol)
        val context = MutationContext(pluginContext, builder, containingFunction)

        val variants = operator.variants(original, context)
        if (variants.isEmpty()) {
            return transformConstWithOperators(original, containingFunction, remainingOperators)
        }

        val pointId = generatePointId()
        val variantCount = variants.size
        val sourceLocation = getSourceLocation(original)
        val originalOperator = operator.originalDescription(original)
        val variantOperators = variants.joinToString(",") { it.description }
        val lineNumber = currentFile?.fileEntry?.getLineNumber(original.startOffset)?.plus(1) ?: 0
        val occurrenceOnLine = nextOccurrenceOnLine(lineNumber, originalOperator)

        fun createCheckCall() = builder.irCall(checkFn).also { call ->
            call.arguments[0] = builder.irGetObject(registryClass)
            call.arguments[1] = builder.irString(pointId)
            call.arguments[2] = builder.irInt(variantCount)
            call.arguments[3] = builder.irString(sourceLocation)
            call.arguments[4] = builder.irString(originalOperator)
            call.arguments[5] = builder.irString(variantOperators)
            call.arguments[6] = builder.irInt(occurrenceOnLine)
        }

        return IrWhenImpl(
            startOffset = original.startOffset,
            endOffset = original.endOffset,
            type = original.type,
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
                result = transformConstWithOperators(original, containingFunction, remainingOperators)
            )
        }
    }

    /**
     * Applies matching function body operators to a function declaration.
     *
     * Wraps the function body in a when expression that either skips the body
     * entirely (mutation active) or executes normally (else branch):
     * ```
     * fun save(entity: Entity) {
     *     when {
     *         MutationRegistry.check(...) == 0 -> { }  // empty - skip body
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

    /**
     * Checks if a class FQN matches any of the configured target patterns from the Gradle config.
     * Supports glob-style patterns: exact match, single-segment wildcard (*), and
     * multi-segment wildcard (**).
     */
    private fun matchesTargetPattern(declaration: IrClass): Boolean {
        if (compiledTargetPatterns.isEmpty()) return false
        val fqName = declaration.fqNameWhenAvailable?.asString() ?: return false
        return compiledTargetPatterns.any { it.matches(fqName) }
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
                "[mutflow] WARNING: Could not read source file $filePath - " +
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

        suppressedLinesCache[filePath] = suppressed
        return suppressed
    }
}
