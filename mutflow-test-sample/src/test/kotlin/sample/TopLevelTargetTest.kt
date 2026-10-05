package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Top-level functions get mutated through `@file:MutationTarget`; before, a file of them had no
 * mutations and every test of it scored nothing. Killing every mutant here is what proves the
 * functions are targets at all: a file without mutations passes vacuously.
 */
@MutFlowTest
class TopLevelTargetTest {

    @Test
    fun `exceeds is strict`() {
        assertTrue(MutFlow.underTest { exceeds(31, 30) })
        assertFalse(MutFlow.underTest { exceeds(30, 30) })
        assertFalse(MutFlow.underTest { exceeds(29, 30) })
    }

    @Test
    fun `isNumberIn accepts digits of the radix and nothing else`() {
        assertTrue(MutFlow.underTest { "1f".isNumberIn(16) })
        assertFalse(MutFlow.underTest { "1g".isNumberIn(16) })
        assertFalse(MutFlow.underTest { "".isNumberIn(10) })
    }

    @Test
    fun `an unmarked class in the file is left alone`() {
        // Deliberately weak: were the class mutated, `> → >=` would survive and fail this class in STRICT mode.
        assertTrue(MutFlow.underTest { UnmarkedInTargetFile().isLarge(100) })
    }
}
