package io.github.anschnapp.mutflow.compiler

/**
 * Central registry of all mutation operators.
 *
 * This is the declarative catalog: the single place where operators are
 * registered, grouped, and looked up. The IR transformer consumes the default
 * sets below; reporting and filtering consume [allDescriptors].
 *
 * The catalog mirrors `docs/mutation-catalog.md` — each operator's [MutatorDescriptor]
 * carries the stable id, group, and status used across backends.
 */
object MutationCatalog {

    /** Operators that match on [org.jetbrains.kotlin.ir.expressions.IrCall] nodes. */
    val callOperators: List<MutationOperator> = listOf(
        RelationalComparisonOperator(),
        ConstantBoundaryOperator(),
        ArithmeticOperator(),
        EqualitySwapOperator(),
        ReferenceEqualityOperator(),
        BooleanInversionOperator(),
        BooleanLogicOperator(),
        UnaryMinusOperator(),
        BitwiseOperator(),
        IncrementOperator(),
        ReplaceNonVoidCallOperator(),
        StringMethodOperator(),
        CollectionMethodOperator()
    )

    /**
     * Experimental call operators, NOT enabled by default.
     *
     * These are high-noise or not yet validated across all backends. Opt in by
     * passing `MutationCatalog.callOperators + MutationCatalog.experimentalCallOperators`
     * to the transformer.
     */
    val experimentalCallOperators: List<MutationOperator> = listOf(
        RemoveIncrementOperator()
    )

    /** Operators that match on [org.jetbrains.kotlin.ir.expressions.IrReturn] nodes. */
    val returnOperators: List<ReturnMutationOperator> = listOf(
        BooleanReturnOperator(),
        NullableReturnOperator(),
        PrimitiveReturnOperator(),
        ObjectReturnOperator(),
        EmptyCollectionReturnOperator()
    )

    /** Operators that match on [org.jetbrains.kotlin.ir.declarations.IrSimpleFunction] bodies. */
    val functionBodyOperators: List<FunctionBodyMutationOperator> = listOf(
        VoidFunctionBodyOperator()
    )

    /**
     * Operators that match on [org.jetbrains.kotlin.ir.expressions.IrWhen] expressions.
     *
     * [BooleanLogicOperator] is registered in both the call and when lists because
     * `&&`/`||` appear as an intrinsic `IrCall` in some compilations and as an
     * `IrWhen` in others; only one form is present per compilation, so exactly one
     * mutation point is generated.
     */
    val whenOperators: List<WhenMutationOperator> = listOf(
        BooleanLogicOperator(),
        ForceConditionalOperator(),
        ElvisOperator(),
        SafeCallOperator()
    )

    /** Operators that match on [org.jetbrains.kotlin.ir.expressions.IrConst] nodes. */
    val constOperators: List<ConstMutationOperator> = listOf(
        StringLiteralOperator(),
        BooleanConstOperator()
    )

    /**
     * Operators that match on [org.jetbrains.kotlin.ir.expressions.IrConstructorCall] nodes.
     *
     * Constructor calls are a distinct IR node type from [org.jetbrains.kotlin.ir.expressions.IrCall]
     * in Kotlin 2.4.0, so they get their own list and transformer visitor path.
     */
    val constructorCallOperators: List<ConstructorCallMutationOperator> = listOf(
        ConstructorCallOperator()
    )

    /** Operators that match on assignment nodes ([org.jetbrains.kotlin.ir.expressions.IrSetValue]
     * and [org.jetbrains.kotlin.ir.expressions.IrSetField]). */
    val assignmentOperators: List<AssignmentMutationOperator> = listOf(
        AssignConstOperator()
    )

    /**
     * Every registered operator's descriptor, for reporting and filtering.
     *
     * Deduplicated by identity because [BooleanLogicOperator] is registered in both
     * the call and when lists (it handles both IR forms of `&&`/`||`).
     */
    val allDescriptors: List<MutatorDescriptor> =
        (callOperators.map { it.descriptor } +
            experimentalCallOperators.map { it.descriptor } +
            returnOperators.map { it.descriptor } +
            functionBodyOperators.map { it.descriptor } +
            whenOperators.map { it.descriptor } +
            constOperators.map { it.descriptor } +
            constructorCallOperators.map { it.descriptor } +
            assignmentOperators.map { it.descriptor }).distinct()

    /** Descriptors of operators that are safe to enable by default. */
    val stableDescriptors: List<MutatorDescriptor> =
        allDescriptors.filter { it.status == MutatorStatus.STABLE }

    /**
     * Looks up an operator descriptor by its stable id.
     *
     * @return the descriptor, or `null` if no operator with that id is registered.
     */
    fun byId(id: String): MutatorDescriptor? = allDescriptors.firstOrNull { it.id == id }

    /**
     * Returns all descriptors in the given [group].
     */
    fun byGroup(group: MutatorGroup): List<MutatorDescriptor> =
        allDescriptors.filter { it.group == group }

    /**
     * Asserts catalog invariants. Throws [IllegalStateException] if any operator
     * has a duplicate id or a blank field. Called once at plugin registration.
     */
    fun validate() {
        val ids = allDescriptors.map { it.id }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate mutator ids in catalog: $duplicates" }
        allDescriptors.forEach { d ->
            require(d.id.isNotBlank()) { "Mutator id must not be blank" }
            require(d.name.isNotBlank()) { "Mutator name must not be blank for ${d.id}" }
            require(d.description.isNotBlank()) { "Mutator description must not be blank for ${d.id}" }
        }
    }
}
