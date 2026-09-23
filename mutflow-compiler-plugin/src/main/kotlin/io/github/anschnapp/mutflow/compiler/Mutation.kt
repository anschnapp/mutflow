package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrExpression

/**
 * What a [MutationOperator] wants done to a node, and how the transformer emits it.
 *
 * A mutant schema keeps the original and every variant side by side and picks one at runtime,
 * so the question every mutation has to answer is where the operands of the mutated node live.
 * Getting that wrong is not a constant cost: a variant that copies its operands copies
 * everything already instrumented beneath it, and a chain `a + b + c + ...` or
 * `a && b && c && ...` then doubles in size per level until it no longer fits in a JVM method.
 * The three kinds differ exactly in that answer:
 *
 * - [Replace]: the variants stand on their own and share nothing with the original. For nodes
 *   whose variants are constants or an empty body, where there is nothing to share.
 * - [OverOperands]: the operands are evaluated once into temporaries, and the original and
 *   every variant read them. For strictly evaluated nodes (arithmetic, comparisons, calls),
 *   which evaluate every operand whichever variant runs.
 * - [Fused]: a single expression that is the original while the mutation is inactive and the
 *   mutant while it is active. For lazily evaluated nodes (`&&`, `||`), where an operand may
 *   not be evaluated at all and so cannot be hoisted.
 */
sealed interface Mutation {

    /** Description of the original for display, e.g. ">" or "return ...". */
    val originalDescription: String

    /** Description of each variant for display, e.g. [">=", "<"]. Never empty. */
    val variantDescriptions: List<String>

    /**
     * Variants that replace the node outright:
     * ```
     * when { check(...) == 0 -> <variant 0>; ...; else -> <original> }
     * ```
     * A variant is only built when the transformer asks for it and must not reuse the node's
     * operands, which stay in the original. Anything it needs from the node it copies, so keep
     * this kind for variants with nothing (or only a leaf) to copy.
     */
    class Replace(
        override val originalDescription: String,
        val variants: List<Variant>
    ) : Mutation {
        override val variantDescriptions: List<String> get() = variants.map { it.description }

        class Variant(
            val description: String,
            /** Builds the variant; called exactly once. */
            val create: () -> IrExpression
        )
    }

    /**
     * Variants over the node's [operands], each evaluated exactly once:
     * ```
     * {
     *     val variant = check(...)
     *     val operand0 = <operands[0]>
     *     val operand1 = <operands[1]>
     *     when { variant == 0 -> <variant 0 over the operands>; ...; else -> <original over the operands> }
     * }
     * ```
     * The operands are moved into the temporaries, so neither the original nor a variant may
     * use them directly: both are rebuilt by [original] and [Variant.create] from the reads
     * in [Operands]. List the operands in the order the node evaluates them, which is the
     * order they are hoisted in.
     *
     * Several operators may mutate the same node this way (`x > 0` gets a relational and a
     * constant boundary mutation). They must then list the very same operand expressions,
     * which are hoisted once for all of them, and the first operator's [original] is used.
     */
    class OverOperands(
        override val originalDescription: String,
        val operands: List<IrExpression>,
        /** Rebuilds the original over the hoisted operands; called exactly once. */
        val original: (Operands) -> IrExpression,
        val variants: List<Variant>
    ) : Mutation {
        override val variantDescriptions: List<String> get() = variants.map { it.description }

        class Variant(
            val description: String,
            /** Builds the variant over the hoisted operands; called exactly once. */
            val create: (Operands) -> IrExpression
        )
    }

    /**
     * One expression covering both the original and its single variant:
     * ```
     * {
     *     val active = check(...) == 0
     *     <build(active)>
     * }
     * ```
     * [build] moves the node's operands into the result, never copies them, and uses `active`
     * to steer between the original and the mutant (a fresh read on every call). A fused
     * mutation has to be the only non-replacing mutation of its node, since it consumes the
     * operands itself.
     */
    class Fused(
        override val originalDescription: String,
        val variantDescription: String,
        /** Builds the fused expression; called exactly once. */
        val build: (active: () -> IrExpression) -> IrExpression
    ) : Mutation {
        override val variantDescriptions: List<String> get() = listOf(variantDescription)
    }
}

/**
 * The hoisted operands of a [Mutation.OverOperands], in the order it listed them.
 *
 * Every access builds a fresh expression that reads the operand's value, so an operand can be
 * used any number of times, in the original and in every variant.
 */
class Operands(private val reads: List<() -> IrExpression>) {
    operator fun get(index: Int): IrExpression = reads[index]()
    val size: Int get() = reads.size
}
