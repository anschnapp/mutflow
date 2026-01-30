package io.github.anschnapp.mutflow.junit

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutantSurvivedException
import io.github.anschnapp.mutflow.Mutation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.BeforeClassTemplateInvocationCallback
import org.junit.jupiter.api.extension.AfterClassTemplateInvocationCallback
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import java.util.stream.Stream
import kotlin.streams.asStream

/**
 * JUnit 6 extension that orchestrates mutation testing runs.
 *
 * This extension is automatically registered when using @MutFlowTest.
 * It:
 * - Creates a MutFlow session when the test class starts
 * - Provides multiple invocation contexts (baseline + mutation runs)
 * - Selects mutations when creating contexts (after baseline completes)
 * - Manages the session lifecycle (startRun/endRun)
 * - Closes the session when the test class finishes
 */
class MutFlowExtension : ClassTemplateInvocationContextProvider {

    override fun supportsClassTemplate(context: ExtensionContext): Boolean {
        return context.testClass
            .map { it.isAnnotationPresent(MutFlowTest::class.java) }
            .orElse(false)
    }

    override fun provideClassTemplateInvocationContexts(
        context: ExtensionContext
    ): Stream<ClassTemplateInvocationContext> {
        val annotation = context.testClass
            .map { it.getAnnotation(MutFlowTest::class.java) }
            .orElseThrow { IllegalStateException("@MutFlowTest annotation not found") }

        val maxRuns = annotation.maxRuns

        // Count test methods for partial run detection
        val testClass = context.requiredTestClass
        val expectedTestCount = countTestMethods(testClass)

        // Create session for this test class
        val sessionId = MutFlow.createSession(
            selection = annotation.selection,
            shuffle = annotation.shuffle,
            maxRuns = maxRuns,
            expectedTestCount = expectedTestCount
        )

        // Generate invocation contexts lazily
        // Each context is created AFTER the previous run completes
        return generateSequence(0 to null as Mutation?) { (run, _) ->
            val nextRun = run + 1
            when {
                nextRun >= maxRuns -> null // Stop at maxRuns
                nextRun == 1 -> {
                    // After baseline, select first mutation
                    val mutation = MutFlow.selectMutationForRun(sessionId, nextRun)
                    if (mutation != null) nextRun to mutation else null
                }
                else -> {
                    // Select next mutation (previous run completed)
                    val mutation = MutFlow.selectMutationForRun(sessionId, nextRun)
                    if (mutation != null) nextRun to mutation else null
                }
            }
        }
            .map { (run, mutation) -> createInvocationContext(sessionId, run, mutation) }
            .asStream()
            .onClose { MutFlow.closeSession(sessionId) }
    }

    private fun createInvocationContext(
        sessionId: String,
        run: Int,
        mutation: Mutation?
    ): ClassTemplateInvocationContext {
        return object : ClassTemplateInvocationContext {
            override fun getDisplayName(invocationIndex: Int): String {
                return when {
                    run == 0 -> "Baseline"
                    mutation != null -> {
                        val session = MutFlow.getSession(sessionId)
                        val displayName = session?.getDisplayName(mutation)
                            ?: "${mutation.pointId}:${mutation.variantIndex}"
                        "Mutation: $displayName"
                    }
                    else -> "Mutation Run $run"
                }
            }

            override fun getAdditionalExtensions(): List<Extension> {
                return listOf(
                    // Before: start the run with pre-selected mutation
                    BeforeClassTemplateInvocationCallback { _ ->
                        MutFlow.startRun(sessionId, run, mutation)
                        if (run == 0) {
                            println("[mutflow] Starting baseline run (discovery)")
                        } else if (mutation != null) {
                            val session = MutFlow.getSession(sessionId)
                            val displayName = session?.getDisplayName(mutation)
                                ?: "${mutation.pointId}:${mutation.variantIndex}"
                            println("[mutflow] Starting mutation run: $displayName")
                        }
                    },
                    // Track test executions during baseline (for partial run detection)
                    AfterTestExecutionCallback { testContext ->
                        if (run == 0) {
                            val session = MutFlow.getSession(sessionId)
                            session?.trackTestExecution(testContext.uniqueId)
                        }
                    },
                    // Exception handler: during mutation runs, catch failures (= mutation killed)
                    TestExecutionExceptionHandler { context, throwable ->
                        if (run == 0) {
                            // Baseline: let failures propagate normally
                            throw throwable
                        } else {
                            // Mutation run: failure means mutation was killed (success!)
                            val session = MutFlow.getSession(sessionId)
                            session?.markTestFailed(context.displayName)
                            // Don't rethrow - swallow the exception
                        }
                    },
                    // After: record result, check for surviving mutation, then end the run
                    AfterClassTemplateInvocationCallback { _ ->
                        val session = MutFlow.getSession(sessionId)
                        if (session != null && run > 0) {
                            session.recordMutationResult()
                            if (session.didMutationSurvive()) {
                                val survivedMutation = session.getActiveMutation()!!
                                val displayName = session.getDisplayName(survivedMutation)
                                MutFlow.endRun(sessionId)
                                throw MutantSurvivedException(survivedMutation, displayName)
                            }
                        }
                        MutFlow.endRun(sessionId)
                    }
                )
            }
        }
    }

    override fun mayReturnZeroClassTemplateInvocationContexts(
        context: ExtensionContext
    ): Boolean {
        // We always return at least the baseline run
        return false
    }

    /**
     * Counts the number of test methods in a class.
     * Used for partial run detection.
     */
    private fun countTestMethods(testClass: Class<*>): Int {
        return testClass.methods.count { method ->
            method.isAnnotationPresent(Test::class.java)
        }
    }
}
