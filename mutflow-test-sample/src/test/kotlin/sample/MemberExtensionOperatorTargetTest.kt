package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.junit.MutFlowTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Compiling this target is the regression; the tests then kill every mutant of the calls. */
@MutFlowTest
class MemberExtensionOperatorTargetTest {

    private val target = MemberExtensionOperatorTarget()

    @Test
    fun `adds two amounts`() {
        assertEquals(Money(700), MutFlow.underTest { target.total(500, 200) })
    }

    @Test
    fun `subtracts an amount`() {
        assertEquals(Money(300), MutFlow.underTest { target.difference(Money(500), Money(200)) })
    }

    @Test
    fun `scales an amount`() {
        assertEquals(Money(2000), MutFlow.underTest { target.scaled(Money(500), 4) })
    }

    @Test
    fun `shares an amount`() {
        assertEquals(Money(125), MutFlow.underTest { target.share(Money(500), 4) })
    }

    @Test
    fun `keeps the remainder of a share`() {
        assertEquals(Money(2), MutFlow.underTest { target.remainder(Money(500), 3) })
    }
}
