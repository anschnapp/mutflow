package sample

import io.github.anschnapp.mutflow.MutationTarget

/**
 * Test target for exception type swap mutations.
 * Each method throws a different exception with arguments
 * to exercise constructor argument copying.
 */
@MutationTarget
class ExceptionThrower {

    fun throwWithMessage(message: String): RuntimeException {
        throw IllegalArgumentException(message)
    }

    fun throwWithMessageAndCause(message: String, cause: Throwable): RuntimeException {
        throw IllegalArgumentException(message, cause)
    }

    fun throwNullPointer(message: String): NullPointerException {
        throw NullPointerException(message)
    }

    fun throwUnsupported(message: String): RuntimeException {
        throw UnsupportedOperationException(message)
    }

    fun throwArithmetic(): RuntimeException {
        throw ArithmeticException("division by zero")
    }

    fun throwIndexOutOfBounds(message: String): IndexOutOfBoundsException {
        throw IndexOutOfBoundsException(message)
    }

    fun throwClassCast(message: String): ClassCastException {
        throw ClassCastException(message)
    }

    fun throwNumber(format: String): NumberFormatException {
        throw NumberFormatException(format)
    }

    fun throwNoSuchElement(message: String): NoSuchElementException {
        throw NoSuchElementException(message)
    }

    fun throwState(message: String): IllegalStateException {
        throw IllegalStateException(message)
    }
}