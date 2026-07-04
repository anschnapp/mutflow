package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Target class exercising Kotlin null-safety operators (`?:`, `?.`, `!!`).
 *
 * These operators desugar to a compiler-synthesized `x == null` check in IR.
 * mutflow must NOT create `== ↔ !=` mutations for these synthetic null checks:
 * the developer never wrote an equality operator, the display would be
 * misleading, and inverting the internal null check is either an always-crash
 * mutant (safe-call, `!!`) or confusing noise (elvis). See EqualitySwapOperator.
 *
 * Each function below contains ONLY a null-safety construct, so a correctly
 * behaving plugin discovers zero mutation points for this whole class.
 */
@MutationTarget
class NullSafetyTarget {

    /** Elvis: desugars to `when { a == null -> fallback; else -> a }`. */
    fun elvis(a: Int?, fallback: Int): Int = a ?: fallback

    /** Safe call: desugars to `when { s == null -> null; else -> s.length }`. */
    fun safeLength(s: String?): Int? = s?.length

    /** Not-null assertion: desugars to `when { a == null -> throw NPE; else -> a }`. */
    fun bang(a: Int?): Int = a!!
}
