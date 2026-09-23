package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * A hand-written equals over many fields, the shape of a value object without `data`. Every
 * `&&` level used to copy the instrumented chain beneath it, so sixteen terms did not fit in a
 * JVM method any more ("Method too large").
 */
@MutationTarget
class WideEqualityTarget(
    val f1: Int, val f2: Int, val f3: Int, val f4: Int, val f5: Int, val f6: Int, val f7: Int, val f8: Int,
    val f9: Int, val f10: Int, val f11: Int, val f12: Int, val f13: Int, val f14: Int, val f15: Int, val f16: Int
) {
    override fun equals(other: Any?) = other is WideEqualityTarget &&
        f1 == other.f1 && f2 == other.f2 && f3 == other.f3 && f4 == other.f4 &&
        f5 == other.f5 && f6 == other.f6 && f7 == other.f7 && f8 == other.f8 &&
        f9 == other.f9 && f10 == other.f10 && f11 == other.f11 && f12 == other.f12 &&
        f13 == other.f13 && f14 == other.f14 && f15 == other.f15 && f16 == other.f16

    override fun hashCode() = f1 + f16
}
