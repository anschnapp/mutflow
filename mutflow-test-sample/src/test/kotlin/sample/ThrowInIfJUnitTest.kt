package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * End-to-end coverage for mutations that are only reached on a throwing path.
 *
 * The assertion lives outside the `underTest {}` block, which is how such a
 * test is naturally written - the exception escapes the block. Mutation points
 * reached before the throw must still be discovered, otherwise the exception
 * type swap on the throw is never tested.
 *
 * Mutations on ThrowInIfTarget.fetch and what kills them:
 * - void body removal: `throws when empty` (no exception without the body)
 * - `empty` → `!empty`: both tests
 * - IllegalStateException → IllegalArgumentException: the assertThrows type
 */
@MutFlowTest
class ThrowInIfJUnitTest {

    private val target = ThrowInIfTarget()

    @Test
    fun `throws IllegalStateException when empty`() {
        assertThrows<IllegalStateException> {
            MutFlow.underTest { target.fetch(empty = true, id = 7) }
        }
    }

    @Test
    fun `does not throw when not empty`() {
        MutFlow.underTest { target.fetch(empty = false, id = 7) }
    }
}
