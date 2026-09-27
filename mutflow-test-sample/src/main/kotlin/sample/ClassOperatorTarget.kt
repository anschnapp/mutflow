package sample

import io.github.anschnapp.mutflow.MutationTarget

/** A class with operators of its own, whose `minus` does not take a [Vec]. */
data class Vec(val v: Int) {
    operator fun plus(o: Vec): Vec = Vec(v + o.v)

    operator fun minus(o: Int): Vec = Vec(v - o)
}

/**
 * `Vec + Vec` has no `minus` of the same signature. Only primitives fall back to the first
 * operator of that name: here that would be `Vec.minus(Int)` called with a [Vec], a mutant that
 * fails with a `ClassCastException` instead of computing anything, so there is no point.
 */
@MutationTarget
class ClassOperatorTarget {

    fun add(a: Vec, b: Vec): Vec = a + b
}
