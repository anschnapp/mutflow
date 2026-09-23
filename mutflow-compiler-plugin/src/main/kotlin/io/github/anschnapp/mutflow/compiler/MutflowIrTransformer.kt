package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.impl.IrBranchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
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
 * Uses an extensible [MutationOperator] mechanism to support different
 * categories of mutations (comparisons, arithmetic, etc.). Every node kind goes the same
 * way: the operators' [Mutation]s become mutation points ([collectPoints]), which are then
 * emitted around the node according to their kind ([emit]).
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class MutflowIrTransformer(
    private val pluginContext: IrPluginContext,
    private val callOperators: List<MutationOperator<IrCall>> = defaultCallOperators(),
    private val returnOperators: List<MutationOperator<IrReturn>> = defaultReturnOperators(),
    private val functionBodyOperators: List<MutationOperator<IrSimpleFunction>> = defaultFunctionBodyOperators(),
    private val whenOperators: List<MutationOperator<IrWhen>> = defaultWhenOperators(),
    private val throwOperators: List<MutationOperator<IrThrow>> = defaultThrowOperators(),
    private val valueOperators: List<MutationOperator<IrGetValue>> = defaultValueOperators(),
    private val targetPatterns: List<String> = emptyList()
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

        fun defaultCallOperators(): List<MutationOperator<IrCall>> = listOf(
            RelationalComparisonOperator(),
            ConstantBoundaryOperator(),
            ArithmeticOperator(),
            EqualitySwapOperator(),
            BooleanInversionOperator(),
        )

        fun defaultThrowOperators(): List<MutationOperator<IrThrow>> = listOf(
            ExceptionTypeSwapOperator()
        )

        fun defaultReturnOperators(): List<MutationOperator<IrReturn>> = listOf(
            BooleanReturnOperator(),
            NullableReturnOperator()
        )

        fun defaultFunctionBodyOperators(): List<MutationOperator<IrSimpleFunction>> = listOf(
            VoidFunctionBodyOperator()
        )

        fun defaultWhenOperators(): List<MutationOperator<IrWhen>> = listOf(
            BooleanLogicOperator()
        )

        fun defaultValueOperators(): List<MutationOperator<IrGetValue>> = listOf(
            BooleanVariableInversionOperator()
        )

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

    // Origins of members the compiler writes for the author, in any class. Matched by name,
    // not by the IrDeclarationOrigin constants: the value class ones were renamed between
    // Kotlin 2.4.10 and 2.4.20, and a constant that does not exist in the compiler running
    // the plugin is a NoSuchMethodError at compile time.
    //
    // An author-written member never carries one of these origins. `equals` spelled out by
    // hand is DEFINED, and so is a property with its own `get() = ...` body, so overriding
    // a generated member brings its mutations back.
    private val compilerGeneratedOriginNames = setOf(
        "GENERATED_DATA_CLASS_MEMBER",
        "GENERATED_INLINE_CLASS_MEMBER",
        "GENERATED_SINGLE_FIELD_VALUE_CLASS_MEMBER",
        "GENERATED_FULL_VALUE_CLASS_MEMBER",
        "GENERATED_MULTI_FIELD_VALUE_CLASS_MEMBER",
        "DEFAULT_PROPERTY_ACCESSOR",
        "DELEGATED_MEMBER"
    )
    private var mutationPointCounter = 0

    // Tracks how many times the same (lineNumber, originalOperator) pair has been seen
    // within the current class, so we can disambiguate display names.
    // Key: "lineNumber:originalOperator", Value: current count (1-based)
    private val lineOperatorOccurrences = mutableMapOf<String, Int>()

    // Comment-based line suppression (mutflow:ignore / mutflow:falsePositive)
    private var suppressedLines: Set<Int> = emptySet()
    private val suppressedLinesCache = mutableMapOf<String, Set<Int>>()

    // Compiled target patterns from Gradle config (glob-style → regex)
    private val compiledTargetPatterns: List<Regex> = compileTargetPatterns(targetPatterns)

    /**
     * Calls that sit in statement position with their value discarded. Recorded while
     * entering a block, before its statements are transformed, so the identity of the
     * original call still matches when [visitCall] reaches it. Only the outermost call
     * of a statement is discarded: in `rows.add(x > 0)` the `>` result is used by `add`.
     */
    private val discardedCalls: MutableSet<IrCall> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        body.statements.forEach { recordDiscardedCall(it) }
        return super.visitBlockBody(body)
    }

    override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
        // The last statement is the block's value unless the block itself is Unit.
        val statements = expression.statements
        statements.forEachIndexed { index, statement ->
            if (index < statements.lastIndex || expression.type.isUnit()) recordDiscardedCall(statement)
        }
        return super.visitContainerExpression(expression)
    }

    private fun recordDiscardedCall(statement: IrStatement) {
        val expression = if (statement is IrTypeOperatorCall && statement.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) {
            statement.argument
        } else {
            statement
        }
        // The discarded value can come from a level deeper: a branch result of an `if` or
        // `when`, a `try`/`catch` result, the value of a block such as a safe call.
        when (expression) {
            is IrCall -> discardedCalls.add(expression)
            is IrWhen -> expression.branches.forEach { recordDiscardedCall(it.result) }
            is IrTry -> {
                recordDiscardedCall(expression.tryResult)
                expression.catches.forEach { recordDiscardedCall(it.result) }
            }
            is IrContainerExpression -> expression.statements.lastOrNull()?.let { recordDiscardedCall(it) }
            else -> {}
        }
    }

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
                || matchesTargetPattern(declaration)
        currentClass = declaration

        debug("  isInMutationTarget: $isInMutationTarget")

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

        // Members the compiler wrote, not the author: data/value class members, the
        // `return field` getter behind a plain property, `by` delegation forwarders.
        // Their mutants land on a declaration line with no such code on it, so no test
        // can kill them, and selection serves least-touched points first, so the noise
        // eats the run budget. The generated equals of a wide data class is also where
        // one switch per property comparison breaks the JVM's 64 KB method limit.
        if (isInMutationTarget && declaration.origin.name in compilerGeneratedOriginNames) {
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
        if (!shouldMutate(transformed.startOffset)) return transformed
        val fn = currentFunction ?: return transformed

        val context = mutationContext(fn, resultUsed = transformed !in discardedCalls)
        val points = collectPoints(transformed, callOperators, context, stack = true, transformed.startOffset)
        return emit(points, transformed, transformed.type, context, transformed.startOffset, transformed.endOffset)
    }

    override fun visitThrow(expression: IrThrow): IrExpression {
        // First, transform children (bottom-up for nested expressions)
        val transformed = super.visitThrow(expression) as IrThrow
        if (!shouldMutate(transformed.startOffset)) return transformed
        val fn = currentFunction ?: return transformed

        // The thrown value is mutated, not the throw. Its mutation is typed Throwable since
        // sibling exception types (e.g. IllegalArgumentException, IllegalStateException)
        // share only Throwable as a common supertype.
        val thrown = transformed.value
        val context = mutationContext(fn)
        val points = collectPoints(transformed, throwOperators, context, stack = true, thrown.startOffset)
        transformed.value = emit(
            points, thrown, pluginContext.irBuiltIns.throwableType, context, thrown.startOffset, thrown.endOffset
        )
        return transformed
    }

    override fun visitGetValue(expression: IrGetValue): IrExpression {
        val transformed = super.visitGetValue(expression) as IrGetValue
        if (!shouldMutate(transformed.startOffset)) return transformed
        val fn = currentFunction ?: return transformed

        val context = mutationContext(fn)
        val points = collectPoints(transformed, valueOperators, context, stack = true, transformed.startOffset)
        return emit(points, transformed, transformed.type, context, transformed.startOffset, transformed.endOffset)
    }

    override fun visitReturn(expression: IrReturn): IrExpression {
        // First, transform the return value (bottom-up for nested expressions)
        val transformed = super.visitReturn(expression) as IrReturn
        if (!shouldMutate(transformed.startOffset)) return transformed
        val fn = currentFunction ?: return transformed

        // Return operators do not stack: only the first matching one is applied.
        val value = transformed.value
        val context = mutationContext(fn)
        val points = collectPoints(transformed, returnOperators, context, stack = false, value.startOffset)
        if (points.isEmpty()) return transformed

        // Type the mutation by the return's TARGET, not the enclosing function: a non-local return
        // inside an inline lambda (`x?.let { return it }`) sits in a lambda whose return type is
        // Nothing, and a Nothing-typed when would make the backend cast the value to Void.
        val blockType = (transformed.returnTargetSymbol.owner as? IrFunction)?.returnType ?: fn.returnType

        return IrReturnImpl(
            startOffset = transformed.startOffset,
            endOffset = transformed.endOffset,
            type = transformed.type,
            returnTargetSymbol = transformed.returnTargetSymbol,
            value = emit(points, value, blockType, context, transformed.startOffset, transformed.endOffset)
        )
    }

    override fun visitWhen(expression: IrWhen): IrExpression {
        // First, transform children (bottom-up for nested expressions)
        val transformed = super.visitWhen(expression) as IrWhen
        if (!shouldMutate(transformed.startOffset)) return transformed
        val fn = currentFunction ?: return transformed

        val context = mutationContext(fn)
        val points = collectPoints(transformed, whenOperators, context, stack = true, transformed.startOffset)
        return emit(points, transformed, transformed.type, context, transformed.startOffset, transformed.endOffset)
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

        // A block body keeps its own scope: the check goes inside it, never around it.
        // Wrapping would push the body's declarations into an inner scope, and a do-while
        // condition may read one of them (`do { val next = it.next() } while (next != null)`),
        // which then fails in codegen with "No mapping for symbol".
        val bodyBlock = body as? IrContainerExpression
        if (bodyBlock != null) {
            // A `for` loop is lowered to a while loop whose body block must start with the
            // loop-variable declarations (`val x = iterator.next()`): ForLoopsLowering
            // pattern-matches them and fails with "No 'next' statement in for-loop" if
            // anything precedes them, so the check goes after those declarations.
            val insertAt = if (loop.origin == IrStatementOrigin.FOR_LOOP_INNER_WHILE) {
                bodyBlock.statements.indexOfFirst { it !is IrVariable }
                    .let { if (it < 0) bodyBlock.statements.size else it }
            } else {
                0
            }
            bodyBlock.statements.add(insertAt, checkCall)
            return
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

    private fun shouldMutate(startOffset: Int): Boolean =
        isInMutationTarget && !isInSuppressedScope && !isLineSuppressedByComment(startOffset)

    private fun mutationContext(function: IrSimpleFunction, resultUsed: Boolean = true) = MutationContext(
        pluginContext,
        DeclarationIrBuilder(pluginContext, function.symbol),
        function,
        resultUsed
    )

    /** A mutation that has been given its identity at runtime. */
    private class Point(
        val id: String,
        val sourceLocation: String,
        val occurrenceOnLine: Int,
        val mutation: Mutation
    )

    /**
     * Asks [operators] for their mutations of [node] and turns each one into a mutation point.
     *
     * With [stack] every matching operator contributes a point (`x > 0` gets one from the
     * relational and one from the constant boundary operator), in operator order; without it
     * only the first matching operator is asked, and when it has nothing to offer the node
     * stays unmutated.
     */
    private fun <T : IrElement> collectPoints(
        node: T,
        operators: List<MutationOperator<T>>,
        context: MutationContext,
        stack: Boolean,
        sourceOffset: Int
    ): List<Point> {
        if (checkFunction == null || mutationRegistryClass == null) {
            debug("ERROR: MutationRegistry.check not found on classpath")
            return emptyList()
        }
        val matching = operators.filter { it.matches(node) }
        val asked = if (stack) matching else matching.take(1)
        if (asked.isEmpty()) return emptyList()

        val sourceLocation = sourceLocation(sourceOffset)
        val lineNumber = currentFile?.fileEntry?.getLineNumber(sourceOffset)?.plus(1) ?: 0
        return asked.mapNotNull { operator ->
            val mutation = operator.mutation(node, context)
                ?.takeIf { it.variantDescriptions.isNotEmpty() }
                ?: return@mapNotNull null
            Point(
                id = generatePointId(),
                sourceLocation = sourceLocation,
                occurrenceOnLine = nextOccurrenceOnLine(lineNumber, mutation.originalDescription),
                mutation = mutation
            ).also {
                debug("MUTATION: ${mutation.originalDescription} at $sourceLocation (occurrence #${it.occurrenceOnLine}) " +
                    "-> variants: ${mutation.variantDescriptions.joinToString(",")}")
            }
        }
    }

    /**
     * Emits [points] around the node whose unmutated form is [original], typed [type].
     *
     * [Mutation.Replace] points become switches with inline check() calls, outermost first,
     * each passing on to the next through its else branch:
     * ```
     * when {
     *     MutationRegistry.check(...) == 0 -> variant0
     *     MutationRegistry.check(...) == 1 -> variant1
     *     else -> <next point, or the original>
     * }
     * ```
     * check() is idempotent for the same pointId, so calling it per branch is safe, and a
     * switch needs no temporary at all.
     *
     * The other kinds share one node's operands and are emitted innermost, by [emitHoisted].
     */
    private fun emit(
        points: List<Point>,
        original: IrExpression,
        type: IrType,
        context: MutationContext,
        startOffset: Int,
        endOffset: Int
    ): IrExpression {
        if (points.isEmpty()) return original
        val builder = context.builder

        // Replacing variants are built first: one may copy the node, which has to happen while
        // the node still holds its operands, before a hoisting point moves them out of it.
        val switches = points.mapNotNull { point ->
            val replace = point.mutation as? Mutation.Replace ?: return@mapNotNull null
            point to replace.variants.map { it.create() }
        }
        val hoisting = points.filter { it.mutation !is Mutation.Replace }
        val inner = if (hoisting.isEmpty()) original else emitHoisted(hoisting, type, context, startOffset, endOffset)

        return switches.foldRight(inner) { (point, variants), elseResult ->
            buildSwitch(
                branches = variants.mapIndexed { index, variant ->
                    builder.irEquals(checkCall(builder, point), builder.irInt(index)) to variant
                },
                elseResult = elseResult,
                type = type,
                startOffset = startOffset,
                endOffset = endOffset
            )
        }
    }

    /**
     * Emits the points that evaluate the node's operands themselves: a single
     * [Mutation.Fused] point, or one or more [Mutation.OverOperands] points over the same
     * operands.
     *
     * Every check() runs first, into a temporary, before any operand is evaluated, as it does
     * on a switch: a point must register itself even when evaluating an operand throws.
     * ```
     * {
     *     val mutflowVariant = MutationRegistry.check(...)       // one per point
     *     val mutflowOperand = <operand>                          // one per operand
     *     when {
     *         mutflowVariant == 0 -> <variant 0 over the operands>
     *         ...
     *         else -> <original over the operands>
     *     }
     * }
     * ```
     * Nothing is duplicated, so a chain `a + b + c + ...` grows by one block per term however
     * it is nested. A fused point instead gets `val mutflowActive = check(...) == 0` and builds
     * its own expression from it.
     *
     * The temporaries' parent is set to the containing function explicitly;
     * [visitSimpleFunction] repairs it afterwards for a temporary that ends up in a lambda.
     */
    private fun emitHoisted(
        points: List<Point>,
        type: IrType,
        context: MutationContext,
        startOffset: Int,
        endOffset: Int
    ): IrExpression {
        val builder = context.builder
        val fused = points.mapNotNull { it.mutation as? Mutation.Fused }
        val overOperands = points.mapNotNull { it.mutation as? Mutation.OverOperands }

        // The block carries the node's offsets: an enclosing point locates itself by them (a
        // return of `x > 0` reports the line of its value), and so does the debugger.
        return builder.irBlock(startOffset = startOffset, endOffset = endOffset, resultType = type) {
            fun temporary(value: IrExpression, nameHint: String) =
                irTemporary(value, nameHint = nameHint).also { it.parent = context.containingFunction }

            if (fused.isNotEmpty()) {
                check(points.size == 1) {
                    "A fused mutation consumes its node's operands and cannot share the node " +
                        "with another hoisting mutation: ${points.map { it.mutation.originalDescription }}"
                }
                val active = temporary(builder.irEquals(checkCall(builder, points.single()), builder.irInt(0)), "mutflowActive")
                +fused.single().build { irGet(active) }
            } else {
                val operands = overOperands.first().operands
                check(overOperands.all { it.operands.size == operands.size && it.operands.zip(operands).all { (a, b) -> a === b } }) {
                    "Mutations over operands of the same node must list the same operands: " +
                        points.map { it.mutation.originalDescription }
                }
                val selected = points.map { temporary(checkCall(builder, it), "mutflowVariant") }
                val values = Operands(operands.map { operand ->
                    val read: () -> IrExpression = if (isFreeToRepeat(operand)) {
                        { operand.deepCopyWithSymbols() }
                    } else {
                        temporary(operand, "mutflowOperand").let { hoisted -> { irGet(hoisted) } }
                    }
                    read
                })
                val branches = overOperands.flatMapIndexed { pointIndex, mutation ->
                    mutation.variants.mapIndexed { index, variant ->
                        builder.irEquals(irGet(selected[pointIndex]), builder.irInt(index)) to variant.create(values)
                    }
                }
                +buildSwitch(branches, overOperands.first().original(values), type, startOffset, endOffset)
            }
        }
    }

    /**
     * True for an operand that can be restated instead of hoisted: a constant, or a read of a
     * value that cannot change (a parameter or a `val`). Restating it where it is used gives the
     * same value as evaluating it up front, without a temporary.
     */
    private fun isFreeToRepeat(operand: IrExpression): Boolean = when (operand) {
        is IrConst -> true
        is IrGetValue -> when (val value = operand.symbol.owner) {
            is IrValueParameter -> true
            is IrVariable -> !value.isVar
            else -> false
        }
        else -> false
    }

    private fun buildSwitch(
        branches: List<Pair<IrExpression, IrExpression>>,
        elseResult: IrExpression,
        type: IrType,
        startOffset: Int,
        endOffset: Int
    ): IrExpression = IrWhenImpl(startOffset = startOffset, endOffset = endOffset, type = type, origin = null).apply {
        for ((condition, result) in branches) {
            this.branches += IrBranchImpl(startOffset = startOffset, endOffset = endOffset, condition = condition, result = result)
        }
        this.branches += IrElseBranchImpl(
            startOffset = startOffset,
            endOffset = endOffset,
            condition = IrConstImpl.boolean(startOffset, endOffset, pluginContext.irBuiltIns.booleanType, true),
            result = elseResult
        )
    }

    /** A fresh `MutationRegistry.check(...)` call for [point]. */
    private fun checkCall(builder: IrBuilderWithScope, point: Point): IrExpression =
        builder.irCall(checkFunction!!).also { call ->
            call.arguments[0] = builder.irGetObject(mutationRegistryClass!!)
            call.arguments[1] = builder.irString(point.id)
            call.arguments[2] = builder.irInt(point.mutation.variantDescriptions.size)
            call.arguments[3] = builder.irString(point.sourceLocation)
            call.arguments[4] = builder.irString(point.mutation.originalDescription)
            call.arguments[5] = builder.irString(point.mutation.variantDescriptions.joinToString(","))
            call.arguments[6] = builder.irInt(point.occurrenceOnLine)
        }

    /**
     * Applies the first matching function body operator to a function declaration.
     *
     * The body's statements move into a block that serves as the original, so a replacing
     * variant swaps out the whole body:
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
        val body = declaration.body as? IrBlockBody ?: return
        val context = mutationContext(declaration)
        val points = collectPoints(declaration, functionBodyOperators, context, stack = false, declaration.startOffset)
        if (points.isEmpty()) return

        val unitType = pluginContext.irBuiltIns.unitType
        val originalBlock = IrBlockImpl(
            startOffset = declaration.startOffset,
            endOffset = declaration.endOffset,
            type = unitType,
            origin = null
        ).apply {
            statements.addAll(body.statements)
        }
        val mutated = emit(points, originalBlock, unitType, context, declaration.startOffset, declaration.endOffset)

        // Replace body statements with the single mutated expression
        body.statements.clear()
        body.statements.add(mutated)
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
     * Extracts the source location of an IR offset.
     * Returns format like "Calculator.kt:5" for IntelliJ clickable links.
     */
    private fun sourceLocation(startOffset: Int): String {
        val file = currentFile ?: return "unknown:0"
        val fileName = file.fileEntry.name.substringAfterLast('/')
        val lineNumber = file.fileEntry.getLineNumber(startOffset) + 1
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

        debug("Parsed suppressed lines for $filePath: $suppressed")
        suppressedLinesCache[filePath] = suppressed
        return suppressed
    }
}
