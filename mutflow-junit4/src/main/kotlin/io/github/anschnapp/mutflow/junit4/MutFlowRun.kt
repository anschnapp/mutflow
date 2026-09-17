package io.github.anschnapp.mutflow.junit4

import io.github.anschnapp.mutflow.MutFlow
import io.github.anschnapp.mutflow.MutFlowSession
import io.github.anschnapp.mutflow.MutantSurvivedException
import io.github.anschnapp.mutflow.Mutation
import io.github.anschnapp.mutflow.MutationTimedOutException
import io.github.anschnapp.mutflow.Selection
import io.github.anschnapp.mutflow.SessionId
import io.github.anschnapp.mutflow.Shuffle
import io.github.anschnapp.mutflow.VerificationMode
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.junit.runner.notification.StoppedByUserException
import org.junit.runners.model.Statement

/**
 * The mutation run loop for JUnit 4, the counterpart of the JUnit 6 class-template extension.
 *
 * [run] executes the whole test class once as baseline (mutation discovery), then once per
 * discovered mutation, each time through [runOnce]. A mutation run stops at its first failing
 * test, as its verdict is settled by then. Every mutation run is reported to the notifier as a
 * synthetic test named after the mutation: it fails with [MutantSurvivedException] when the
 * mutant survived in STRICT mode, and with [MutationTimedOutException] when it timed out.
 *
 * The class is independent of any particular runner so that runners with their own threading,
 * such as Robolectric, can reuse it: [wrap] takes the session from this object rather than from
 * the thread-keyed `MutFlow.underTest`, which would not find it on a foreign thread.
 */
class MutFlowRun(private val testClass: Class<*>) {

    private val settings: MutFlowTest? = testClass.getAnnotation(MutFlowTest::class.java)

    /** Whether tests should be wrapped whole; see [MutFlowTest.wrapTestMethods]. */
    val wrapTestMethods: Boolean = settings?.wrapTestMethods ?: false

    @Volatile
    private var session: MutFlowSession? = null

    /** Runs [inner] inside the open session's `underTest`. */
    fun wrap(inner: Statement): Statement = object : Statement() {
        override fun evaluate() {
            val session = checkNotNull(session) { "No MutFlow session is open; run this class with MutFlowRunner" }
            session.underTest { inner.evaluate() }
        }
    }

    fun run(notifier: RunNotifier, runOnce: (RunNotifier) -> Unit) {
        val maxRuns = resolveMaxRuns(settings?.maxRuns ?: Int.MAX_VALUE)
        val timeoutMs = resolveTimeoutMs(settings?.timeoutMs ?: 60_000)
        val mode = resolveVerificationMode(settings?.verificationMode ?: VerificationMode.STRICT)

        val sessionId = MutFlow.createSession(
            selection = Selection.MostLikelyStable,
            shuffle = Shuffle.PerChange,
            maxRuns = maxRuns,
            expectedTestCount = countTestMethods(testClass),
            traps = settings?.traps?.toList().orEmpty(),
            includeTargets = settings?.includeTargets?.map { it.java.name }.orEmpty(),
            excludeTargets = settings?.excludeTargets?.map { it.java.name }.orEmpty(),
            timeoutMs = timeoutMs,
            verificationMode = mode
        )
        val session = checkNotNull(MutFlow.getSession(sessionId))
        this.session = session
        try {
            runBaseline(sessionId, session, notifier, runOnce)
            when (mode) {
                VerificationMode.DISABLED -> {
                    println("[mutflow] Verification mode: DISABLED - skipping mutation runs")
                    return
                }
                VerificationMode.LENIENT -> println("[mutflow] Verification mode: LENIENT - surviving mutations will not cause test failure")
                VerificationMode.STRICT -> Unit
            }
            var run = 1
            while (run < maxRuns) {
                val mutation = MutFlow.selectMutationForRun(sessionId, run) ?: break
                runMutation(sessionId, session, run, mutation, notifier, runOnce)
                run++
            }
        } finally {
            this.session = null
            MutFlow.closeSession(sessionId)
        }
    }

    private fun runBaseline(sessionId: SessionId, session: MutFlowSession, notifier: RunNotifier, runOnce: (RunNotifier) -> Unit) {
        val listener = object : RunListener() {
            override fun testFinished(description: Description) = session.trackTestExecution(description.displayName)
            override fun testFailure(failure: Failure) = session.markBaselineFailure()
        }
        println("[mutflow] Starting baseline run (discovery)")
        MutFlow.startRun(sessionId, 0, null)
        notifier.addListener(listener)
        try {
            runOnce(notifier)
        } finally {
            notifier.removeListener(listener)
            MutFlow.endRun(sessionId)
        }
    }

    private fun runMutation(
        sessionId: SessionId,
        session: MutFlowSession,
        run: Int,
        mutation: Mutation,
        notifier: RunNotifier,
        runOnce: (RunNotifier) -> Unit
    ) {
        val displayName = session.getDisplayName(mutation)
        println("[mutflow] Starting mutation run: $displayName")
        MutFlow.startRun(sessionId, run, mutation)

        // Failures during a mutation run are the mutant being killed; they must not reach the
        // outer notifier. The first one settles the verdict, so the run is stopped there.
        var timeout: MutationTimedOutException? = null
        val mutationNotifier = RunNotifier()
        mutationNotifier.addListener(object : RunListener() {
            override fun testFailure(failure: Failure) {
                val exception = failure.exception
                if (exception is MutationTimedOutException) {
                    timeout = exception
                    session.markTestTimedOut()
                } else {
                    session.markTestFailed(failure.description.methodName ?: failure.description.displayName)
                }
                mutationNotifier.pleaseStop()
            }
        })
        try {
            runOnce(mutationNotifier)
        } catch (_: StoppedByUserException) {
        }
        session.recordMutationResult()
        val survived = session.didMutationSurvive()
        MutFlow.endRun(sessionId)

        val description = Description.createTestDescription(testClass, "Mutation: $displayName")
        notifier.fireTestStarted(description)
        when {
            timeout != null -> notifier.fireTestFailure(Failure(description, timeout))
            survived && session.getVerificationMode() == VerificationMode.STRICT ->
                notifier.fireTestFailure(Failure(description, MutantSurvivedException(mutation, displayName)))
            survived -> println("[mutflow] Mutation survived (lenient): $displayName")
        }
        notifier.fireTestFinished(description)
    }

    /** Counts `@Test` methods; used by the session to detect a partial run (a single test picked in the IDE). */
    private fun countTestMethods(testClass: Class<*>): Int = testClass.methods.count { it.isAnnotationPresent(Test::class.java) }

    private fun <T> resolveFromEnv(name: String, annotationValue: T, parse: (String) -> T?): T {
        val envValue = System.getenv(name) ?: return annotationValue
        val parsed = parse(envValue)
        if (parsed == null) {
            println("[mutflow] WARNING: Invalid $name value: '$envValue'. Falling back to annotation value: $annotationValue")
            return annotationValue
        }
        return parsed
    }

    private fun resolveMaxRuns(annotationValue: Int): Int =
        resolveFromEnv("MUTFLOW_MAX_RUNS", annotationValue) { it.toIntOrNull()?.takeIf { n -> n > 0 } }

    private fun resolveTimeoutMs(annotationValue: Long): Long =
        resolveFromEnv("MUTFLOW_TIMEOUT_MS", annotationValue) { it.toLongOrNull()?.takeIf { n -> n > 0 } }

    private fun resolveVerificationMode(annotationValue: VerificationMode): VerificationMode =
        resolveFromEnv("MUTFLOW_VERIFICATION_MODE", annotationValue) { value ->
            VerificationMode.entries.firstOrNull { it.name == value.uppercase() }.also {
                if (it == null) println("[mutflow] Valid values: ${VerificationMode.entries.joinToString()}")
            }
        }
}
