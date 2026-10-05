package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * `&&` and `||` over a platform-typed Java `Boolean` that is null. Kotlin unboxes the left
 * operand, so both throw a NullPointerException, and the instrumented code must throw it too.
 */
@MutationTarget
class PlatformBooleanTarget {

    fun and(b: Boolean): Boolean = JavaBooleans.missing() && b

    fun or(b: Boolean): Boolean = JavaBooleans.missing() || b
}
