package io.github.anschnapp.mutflow

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Lives in commonTest so it runs on every target (jvmTest, linuxX64Test, ...),
// with plain camelCase names (Kotlin/Native rejects backtick names).
//
// The tests play the role of compiler-injected code by calling
// MutationRegistry.check()/checkTimeout() directly inside the underTest
// blocks - the same calls the IR transformer injects into @MutationTarget
// classes. File IO is replaced by capturing writer lambdas.
class ProcessRunTest {

    // Captured discovery writes: (path, points, touchCounts), latest last.
    private val discoveryWrites = mutableListOf<Triple<String, List<DiscoveredPoint>, Map<String, Int>>>()

    // Captured result writes as (path, pointId, variantIndex, touched, timedOut).
    private data class ResultWrite(
        val path: String,
        val pointId: String,
        val variantIndex: Int,
        val touched: Boolean,
        val timedOut: Boolean
    )
    private val resultWrites = mutableListOf<ResultWrite>()

    private fun processRun(mode: ProcessRunMode) = ProcessRun(
        mode = mode,
        writeDiscovery = { path, points, touchCounts ->
            discoveryWrites.add(Triple(path, points, touchCounts))
        },
        writeResult = { path, pointId, variantIndex, touched, timedOut ->
            resultWrites.add(ResultWrite(path, pointId, variantIndex, touched, timedOut))
        }
    )

    // Simulates one compiler-injected mutation point check.
    private fun checkPoint(pointId: String): Int? = MutationRegistry.check(
        pointId = pointId,
        variantCount = 2,
        sourceLocation = "Fake.kt:1",
        originalOperator = ">",
        variantOperators = ">=,<"
    )

    @BeforeTest
    fun resetRegistry() {
        MutationRegistry.reset()
    }

    @AfterTest
    fun cleanupRegistry() {
        MutationRegistry.reset()
    }

    // ==================== Inactive mode ====================

    @Test
    fun inactiveModeRunsBlockWithoutSessionOrWrites() {
        val run = processRun(ProcessRunMode.Inactive)

        val result = run.underTest {
            // Without a registry session, check() must return null (= run
            // original code): that is what makes plain un-orchestrated
            // native test runs behave like mutflow is absent.
            assertNull(checkPoint("p1"))
            42
        }

        assertEquals(42, result)
        assertTrue(discoveryWrites.isEmpty())
        assertTrue(resultWrites.isEmpty())
    }

    // ==================== Discovery mode ====================

    @Test
    fun discoveryAccumulatesPointsAndTouchCountsAcrossUnderTestBlocks() {
        val run = processRun(ProcessRunMode.Discovery("disc.json"))

        run.underTest { checkPoint("p1") }
        run.underTest {
            checkPoint("p1")
            checkPoint("p2")
        }

        // One rewrite per underTest block.
        assertEquals(2, discoveryWrites.size)

        val (path, points, touchCounts) = discoveryWrites.last()
        assertEquals("disc.json", path)
        // Insertion order preserved: p1 was discovered first.
        assertEquals(listOf("p1", "p2"), points.map { it.pointId })
        // p1 was touched by both blocks, p2 by one.
        assertEquals(mapOf("p1" to 2, "p2" to 1), touchCounts)
    }

    @Test
    fun discoveryDoesNotActivateAnyMutation() {
        val run = processRun(ProcessRunMode.Discovery("disc.json"))
        run.underTest {
            assertNull(checkPoint("p1"))
        }
    }

    // ==================== Mutation mode ====================

    private fun mutationMode(
        pointId: String = "p1",
        variantIndex: Int = 1,
        resultFilePath: String? = "result.json",
        timeoutMs: Long = 60_000
    ) = ProcessRunMode.Mutation(pointId, variantIndex, resultFilePath, timeoutMs)

    @Test
    fun mutationModeActivatesExactlyTheConfiguredMutation() {
        val run = processRun(mutationMode(pointId = "p1", variantIndex = 1))

        run.underTest {
            assertEquals(1, checkPoint("p1"))
            assertNull(checkPoint("p2"))
        }
    }

    @Test
    fun mutationModeRecordsTouchedInResultFile() {
        val run = processRun(mutationMode())

        run.underTest { checkPoint("p1") }

        assertEquals(
            ResultWrite("result.json", "p1", 1, touched = true, timedOut = false),
            resultWrites.single()
        )
    }

    @Test
    fun mutationModeRecordsUntouchedWhenPointNeverReached() {
        val run = processRun(mutationMode(pointId = "never.Reached_0"))

        run.underTest { checkPoint("p1") }

        assertEquals(false, resultWrites.single().touched)
    }

    @Test
    fun touchedStaysTrueOnceSetEvenIfLaterBlocksMissThePoint() {
        val run = processRun(mutationMode())

        run.underTest { checkPoint("p1") }
        run.underTest { /* does not reach p1 */ }

        assertEquals(2, resultWrites.size)
        assertTrue(resultWrites.last().touched)
    }

    @Test
    fun killedMutationRethrowsButStillWritesResultWithTouched() {
        val run = processRun(mutationMode())

        assertFailsWith<AssertionError> {
            run.underTest {
                checkPoint("p1")
                throw AssertionError("test killed the mutant")
            }
        }

        val write = resultWrites.single()
        assertTrue(write.touched)
        assertEquals(false, write.timedOut)
        // The registry session must be cleanly closed despite the exception,
        // otherwise the next underTest block would fail with "Session already
        // active".
        run.underTest { checkPoint("p1") }
        assertEquals(2, resultWrites.size)
    }

    @Test
    fun noResultWritesWhenNoResultFileConfigured() {
        val run = processRun(mutationMode(resultFilePath = null))

        run.underTest { checkPoint("p1") }

        assertTrue(resultWrites.isEmpty())
    }

    @Test
    fun timedOutMutationRethrowsAndSetsTimedOutFlag() {
        // 1ms deadline; the loop below calls the compiler-injected timeout
        // check until it fires. Bounded so a broken implementation fails the
        // test instead of hanging it.
        val run = processRun(mutationMode(timeoutMs = 1))

        assertFailsWith<MutationTimedOutException> {
            run.underTest {
                checkPoint("p1")
                repeat(2_000_000_000) {
                    MutationRegistry.checkTimeout()
                }
                fail("timeout never fired")
            }
        }

        val write = resultWrites.single()
        assertTrue(write.timedOut)
        assertTrue(write.touched)
    }
}
