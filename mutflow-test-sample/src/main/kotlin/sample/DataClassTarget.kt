package sample

import io.github.anschnapp.mutflow.MutationTarget
import kotlin.math.abs

/**
 * A data class target: its generated equals/hashCode/toString/copy/componentN must not be
 * mutated, only the member the author wrote.
 */
@MutationTarget
data class DataClassTarget(val x: Int, val y: Int) {

    fun manhattan(): Int = abs(x) + abs(y)
}
