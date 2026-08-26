package sample

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutationRegistry
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.Shuffle
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the exception type swap mutation operator.
 *
 * The operator matches `IrConstructorCall` nodes for exception types
 * and generates variants that call a sibling exception constructor
 * instead (e.g., `IllegalArgumentException` → `IllegalStateException`).
 *
 * Important: catch exceptions INSIDE the `underTest` block so that
 * `MutationRegistry.withSession` completes normally and records
 * discovered mutation points.
 */
class ExceptionTypeSwapTest {

    private val thrower = ExceptionThrower()

    @BeforeTest
    fun setup() {
        MutationRegistry.reset()
        MutFlow.reset()
    }

    /**
     * Helper: runs the block under mutation testing, catching any
     * exception thrown inside the block and returning it as a string
     * (the class simple name), or "no-exception" if none was thrown.
     *
     * The try-catch MUST be inside the block so that withSession completes
     * normally and mutation points are discovered.
     */
    private fun runAndCapture(run: Int, block: () -> Unit): String {
        return MutFlow.underTest(run = run, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
            try {
                block()
                "no-exception"
            } catch (e: Throwable) {
                e::class.java.simpleName
            }
        }
    }

    @Test
    fun `exception type swap generates mutation point`() {
        runAndCapture(run = 0) {
            thrower.throwWithMessage("bad input")
        }

        val state = MutFlow.getRegistryState()
        val points = state.discoveredPoints.entries.filter { it.key.contains("ExceptionThrower") }
        assertTrue(points.isNotEmpty(), "Should discover mutation points for ExceptionThrower")
        assertTrue(points.any { it.value == 1 }, "Each exception type swap should have 1 variant")
    }

    @Test
    fun `exception type swap changes thrown type on mutation`() {
        // Baseline: throws IllegalArgumentException
        val baseline = runAndCapture(run = 0) {
            thrower.throwWithMessage("bad input")
        }
        assertEquals("IllegalArgumentException", baseline, "Baseline should throw IllegalArgumentException")

        // Mutation: IllegalArgumentException → IllegalStateException
        val mutant = runAndCapture(run = 1) {
            thrower.throwWithMessage("bad input")
        }
        assertEquals("IllegalStateException", mutant,
            "On mutation, should throw IllegalStateException instead of IllegalArgumentException")
    }

    @Test
    fun `exception type swap with two-arg constructor preserves arguments`() {
        val cause = RuntimeException("cause")

        // Baseline: throws IllegalArgumentException(msg, cause)
        val baseline = try {
            MutFlow.underTest(run = 0, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
                try {
                    thrower.throwWithMessageAndCause("wrapper", cause)
                    "no-exception:null"
                } catch (e: IllegalArgumentException) {
                    "IllegalArgumentException:${e.message}"
                } catch (e: IllegalStateException) {
                    "IllegalStateException:${e.message}"
                }
            }
        } catch (e: Throwable) {
            "outer:${e::class.java.simpleName}"
        }

        // Mutant: IllegalStateException(msg, cause)
        val mutant = try {
            MutFlow.underTest(run = 1, selection = Selection.MostLikelyStable, shuffle = Shuffle.PerChange) {
                try {
                    thrower.throwWithMessageAndCause("wrapper", cause)
                    "no-exception:null"
                } catch (e: IllegalArgumentException) {
                    "IllegalArgumentException:${e.message}"
                } catch (e: IllegalStateException) {
                    "IllegalStateException:${e.message}"
                }
            }
        } catch (e: Throwable) {
            "outer:${e::class.java.simpleName}"
        }

        assertTrue(baseline.startsWith("IllegalArgumentException"), "Baseline should be IllegalArgumentException")
        assertTrue(baseline.endsWith(":wrapper"), "Baseline should preserve message 'wrapper'")

        assertTrue(mutant.startsWith("IllegalStateException"),
            "Mutant should throw IllegalStateException (got: $mutant)")
        assertTrue(mutant.endsWith(":wrapper"),
            "Mutant should preserve message 'wrapper' (got: $mutant)")
    }

    @Test
    fun `null pointer exception swap changes thrown type on mutation`() {
        // Baseline: throws NullPointerException
        val baseline = runAndCapture(run = 0) {
            thrower.throwNullPointer("null value")
        }
        assertEquals("NullPointerException", baseline, "Baseline should throw NullPointerException")

        // Mutation: NullPointerException → IllegalArgumentException
        val mutant = runAndCapture(run = 1) {
            thrower.throwNullPointer("null value")
        }
        assertEquals("IllegalArgumentException", mutant,
            "On mutation, should throw IllegalArgumentException instead of NullPointerException")
    }

    @Test
    fun `arithmetic exception swap changes thrown type on mutation`() {
        // Baseline: throws ArithmeticException
        val baseline = runAndCapture(run = 0) {
            thrower.throwArithmetic()
        }
        assertEquals("ArithmeticException", baseline, "Baseline should throw ArithmeticException")

        // Mutation: ArithmeticException → IllegalStateException
        val mutant = runAndCapture(run = 1) {
            thrower.throwArithmetic()
        }
        assertEquals("IllegalStateException", mutant,
            "On mutation, should throw IllegalStateException instead of ArithmeticException")
    }

    @Test
    fun `unsupported operation exception swap changes thrown type on mutation`() {
        val baseline = runAndCapture(run = 0) {
            thrower.throwUnsupported("not supported")
        }
        assertEquals("UnsupportedOperationException", baseline, "Baseline should throw UnsupportedOperationException")

        val mutant = runAndCapture(run = 1) {
            thrower.throwUnsupported("not supported")
        }
        assertEquals("IllegalStateException", mutant,
            "On mutation, should throw IllegalStateException instead of UnsupportedOperationException")
    }

    @Test
    fun `index out of bounds exception swap changes thrown type on mutation`() {
        val baseline = runAndCapture(run = 0) {
            thrower.throwIndexOutOfBounds("invalid index")
        }
        assertEquals("IndexOutOfBoundsException", baseline, "Baseline should throw IndexOutOfBoundsException")

        val mutant = runAndCapture(run = 1) {
            thrower.throwIndexOutOfBounds("invalid index")
        }
        assertEquals("IllegalStateException", mutant,
            "On mutation, should throw IllegalStateException instead of IndexOutOfBoundsException")
    }

    @Test
    fun `class cast exception swap changes thrown type on mutation`() {
        val baseline = runAndCapture(run = 0) {
            thrower.throwClassCast("invalid cast")
        }
        assertEquals("ClassCastException", baseline, "Baseline should throw ClassCastException")

        val mutant = runAndCapture(run = 1) {
            thrower.throwClassCast("invalid cast")
        }
        assertEquals("IllegalArgumentException", mutant,
            "On mutation, should throw IllegalArgumentException instead of ClassCastException")
    }

    @Test
    fun `number format exception swap changes thrown type on mutation`() {
        val baseline = runAndCapture(run = 0) {
            thrower.throwNumber("not a number")
        }
        assertEquals("NumberFormatException", baseline, "Baseline should throw NumberFormatException")

        val mutant = runAndCapture(run = 1) {
            thrower.throwNumber("not a number")
        }
        assertEquals("IllegalStateException", mutant,
            "On mutation, should throw IllegalStateException instead of NumberFormatException")
    }

    @Test
    fun `no such element exception swap changes thrown type on mutation`() {
        val baseline = runAndCapture(run = 0) {
            thrower.throwNoSuchElement("missing element")
        }
        assertEquals("NoSuchElementException", baseline, "Baseline should throw NoSuchElementException")

        val mutant = runAndCapture(run = 1) {
            thrower.throwNoSuchElement("missing element")
        }
        assertEquals("IllegalStateException", mutant,
            "On mutation, should throw IllegalStateException instead of NoSuchElementException")
    }

    @Test
    fun `illegal state exception swap changes thrown type on mutation`() {
        val baseline = runAndCapture(run = 0) {
            thrower.throwState("bad state")
        }
        assertEquals("IllegalStateException", baseline, "Baseline should throw IllegalStateException")

        val mutant = runAndCapture(run = 1) {
            thrower.throwState("bad state")
        }
        assertEquals("IllegalArgumentException", mutant,
            "On mutation, should throw IllegalArgumentException instead of IllegalStateException")
    }
}