package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Target class with non-local returns from inline lambdas (`x?.let { return it }`).
 *
 * The return sits in a lambda whose return type is Nothing while its target is the enclosing
 * function. The nullable-return mutation used to type its generated `when` by the lambda, which
 * made the backend cast the returned value to Void and throw ClassCastException at runtime,
 * even with no mutation active.
 */
@MutationTarget
class NonLocalReturnTarget {

    fun firstPresent(a: Int?, b: Int?): Int? {
        a?.let { return it }
        b?.let { return it }
        return null
    }
}
