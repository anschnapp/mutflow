@file:MutationTarget

package sample

import io.github.anschnapp.mutflow.MutationTarget

/*
 * Top-level functions belong to no class, so they are targeted through the file. The functions
 * here are the kind that end up in a `Utils.kt`: a plain function and an extension.
 */

/** Whether [value] is strictly above [limit]. */
fun exceeds(value: Int, limit: Int): Boolean = value > limit

/** Whether the string is a number in the given [radix], `false` for an empty string. */
fun String.isNumberIn(radix: Int): Boolean = isNotEmpty() && all { it.digitToIntOrNull(radix) != null }

/** A class in a targeted file is not part of the file target; it has to be marked itself. */
class UnmarkedInTargetFile {
    fun isLarge(value: Int): Boolean = value > 10
}
