package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Target for throws that are only reached on a failing path, i.e. the throw
 * escapes the `underTest {}` block of the test that exercises it.
 */
@MutationTarget
class ThrowInIfTarget {

    fun fetch(empty: Boolean, id: Int) {
        if (empty) {
            throw IllegalStateException("No data for id=$id")
        }
    }
}
