package sample

import io.github.anschnapp.mutflow.MutationTarget
import kotlin.math.abs

/**
 * A data class target: its generated equals/hashCode/toString/copy/componentN and the
 * generated accessor behind a plain property must not be mutated, only the members the
 * author wrote.
 */
@MutationTarget
data class DataClassTarget(val x: Int, val y: Int) {

    /** Plain property: the compiler writes its `return field` getter, so it stays clean. */
    val isOrigin: Boolean = x == 0 && y == 0

    /** Accessor the author wrote, so it is mutated like any other body. */
    val isFar: Boolean
        get() = manhattan() > 10

    fun manhattan(): Int = abs(x) + abs(y)
}

/** Members generated for `by` delegation are the compiler's too, so they stay clean. */
interface Distance {
    fun isDistant(): Boolean
}

private class DistanceImpl(private val target: DataClassTarget) : Distance {
    override fun isDistant(): Boolean = target.manhattan() > 10
}

@MutationTarget
class DelegatingDistance(target: DataClassTarget) : Distance by DistanceImpl(target)
