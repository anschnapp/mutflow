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

    fun throwRuntime(message: String): RuntimeException {
        throw RuntimeException(message)
    }
}
