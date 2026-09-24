package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Both parts of the `sample.MultifileTarget` facade are targets through the one pattern naming the
 * facade. Killing every mutant in both proves it: a part without mutations passes vacuously.
 */
@MutFlowTest
class MultifileTargetTest {

    @Test
    fun `isBlankish is strict at two characters`() {
        assertTrue(MutFlow.underTest { isBlankish("") })
        assertTrue(MutFlow.underTest { isBlankish(" a ") })
        assertFalse(MutFlow.underTest { isBlankish("ab") })
        assertFalse(MutFlow.underTest { isBlankish("abc") })
    }

    @Test
    fun `isSmall is strict at ten`() {
        assertTrue(MutFlow.underTest { isSmall(0) })
        assertTrue(MutFlow.underTest { isSmall(9) })
        assertFalse(MutFlow.underTest { isSmall(10) })
        assertFalse(MutFlow.underTest { isSmall(11) })
    }
}
