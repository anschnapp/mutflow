package io.github.anschnapp.mutflow.junit4

import org.junit.runner.notification.RunNotifier
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

/**
 * JUnit 4 runner that mutation-tests a class the way `@MutFlowTest` does on JUnit 6.
 *
 * ```kotlin
 * @RunWith(MutFlowRunner::class)
 * class CalculatorTest {
 *     @Test fun `isPositive returns true for positive numbers`() {
 *         val result = MutFlow.underTest { calculator.isPositive(5) }
 *         assertTrue(result)
 *     }
 * }
 * ```
 *
 * Tests call `MutFlow.underTest {}` around the code under test, exactly as with JUnit 6. With
 * `@MutFlowTest(wrapTestMethods = true)` the runner wraps each whole test method instead, so
 * a suite can be mutation-tested without touching its tests.
 *
 * Runners with their own threading (Robolectric runs the test body on a sandbox thread) can
 * subclass their runner and delegate the same two hooks to a [MutFlowRun].
 */
open class MutFlowRunner(testClass: Class<*>) : BlockJUnit4ClassRunner(testClass) {

    protected val mutflow = MutFlowRun(testClass)

    override fun methodBlock(method: FrameworkMethod): Statement {
        val statement = super.methodBlock(method)
        return if (mutflow.wrapTestMethods) mutflow.wrap(statement) else statement
    }

    override fun run(notifier: RunNotifier) {
        mutflow.run(notifier) { super.run(it) }
    }
}
