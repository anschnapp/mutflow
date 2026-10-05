package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Chains that lean both ways at once: parenthesised `||` groups joined by `&&`, an `&&` group
 * inside an `||`, and a guard whose right operand is itself a chain. Each nesting direction
 * puts the sub-chain in a different operand slot, so this is the shape that catches a fix
 * which only handles one of them.
 */
@MutationTarget
class MixedAssociativityTarget {

    /** `(a || b) && (c || d) && (e || f)`: left-associative spine, right-leaning groups. */
    fun allGroupsSatisfied(a: Boolean, b: Boolean, c: Boolean, d: Boolean, e: Boolean, f: Boolean): Boolean =
        (a || b) && (c || d) && (e || f)

    /** `a && (b || (c && (d || e)))`: alternating operators, nesting to the right. */
    fun nestedAlternating(a: Boolean, b: Boolean, c: Boolean, d: Boolean, e: Boolean): Boolean =
        a && (b || (c && (d || e)))

    /**
     * A null guard whose right operand is a chain. The guard must keep short-circuiting under
     * mutation too: with `&&` swapped for `||`, a null `name` must not be dereferenced when
     * the left operand already decided the result.
     */
    fun nameIsShortAndTrimmed(name: String?): Boolean =
        name != null && (name.length < 10 && name == name.trim())

    /**
     * A chain in a loop condition, where the instrumented expression is re-evaluated on every
     * pass. The mutation switch introduces a block there, which is the shape that broke
     * `do`/`while` scoping once before.
     */
    fun countWhileBothHold(limit: Int): Int {
        var passes = 0
        while (passes < limit && passes < 5) {
            passes++
        }
        return passes
    }
}
