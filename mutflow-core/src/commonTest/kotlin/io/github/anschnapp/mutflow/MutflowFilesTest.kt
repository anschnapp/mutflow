package io.github.anschnapp.mutflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Lives in commonTest so it runs on every target (jvmTest, linuxX64Test, ...).
// Note the plain camelCase test names: Kotlin/Native does not support the
// backtick-with-spaces names used by the older jvmTest suites.
class MutflowFilesTest {

    private val point = DiscoveredPoint(
        pointId = "sample.Calculator_0",
        variantCount = 2,
        sourceLocation = "Calculator.kt:5",
        originalOperator = ">",
        variantOperators = listOf(">=", "<"),
        occurrenceOnLine = 1
    )

    @Test
    fun discoveryJsonContainsAllPointFields() {
        val json = MutflowFiles.buildDiscoveryJson(listOf(point), mapOf("sample.Calculator_0" to 3))

        val expected = "{\"formatVersion\":1,\"points\":[\n" +
            "{\"pointId\":\"sample.Calculator_0\",\"variantCount\":2," +
            "\"sourceLocation\":\"Calculator.kt:5\",\"originalOperator\":\">\"," +
            "\"variantOperators\":[\">=\",\"<\"],\"occurrenceOnLine\":1,\"touchCount\":3}\n" +
            "]}\n"
        assertEquals(expected, json)
    }

    @Test
    fun discoveryJsonWithNoPointsIsEmptyArray() {
        val json = MutflowFiles.buildDiscoveryJson(emptyList(), emptyMap())
        assertEquals("{\"formatVersion\":1,\"points\":[]}\n", json)
    }

    @Test
    fun discoveryJsonSeparatesMultiplePointsWithCommas() {
        val second = point.copy(pointId = "sample.Calculator_1", occurrenceOnLine = 2)
        val json = MutflowFiles.buildDiscoveryJson(listOf(point, second), emptyMap())

        // Untouched points fall back to touchCount 0.
        val expected = "{\"formatVersion\":1,\"points\":[\n" +
            "{\"pointId\":\"sample.Calculator_0\",\"variantCount\":2," +
            "\"sourceLocation\":\"Calculator.kt:5\",\"originalOperator\":\">\"," +
            "\"variantOperators\":[\">=\",\"<\"],\"occurrenceOnLine\":1,\"touchCount\":0},\n" +
            "{\"pointId\":\"sample.Calculator_1\",\"variantCount\":2," +
            "\"sourceLocation\":\"Calculator.kt:5\",\"originalOperator\":\">\"," +
            "\"variantOperators\":[\">=\",\"<\"],\"occurrenceOnLine\":2,\"touchCount\":0}\n" +
            "]}\n"
        assertEquals(expected, json)
    }

    @Test
    fun resultJsonCarriesTouchedAndTimedOutFlags() {
        val json = MutflowFiles.buildResultJson(
            pointId = "sample.Calculator_0",
            variantIndex = 1,
            touched = true,
            timedOut = false
        )
        assertEquals(
            "{\"formatVersion\":1,\"pointId\":\"sample.Calculator_0\"," +
                "\"variantIndex\":1,\"touched\":true,\"timedOut\":false}\n",
            json
        )
    }

    @Test
    fun discoveryJsonRoundTripsThroughParser() {
        val second = point.copy(
            pointId = "sample.Calculator_1",
            sourceLocation = "weird \"name\"\\path.kt:7",
            variantOperators = listOf("<=", "==", "!=")
        )
        val touchCounts = mapOf("sample.Calculator_0" to 3, "sample.Calculator_1" to 1)
        val json = MutflowFiles.buildDiscoveryJson(listOf(point, second), touchCounts)

        val parsed = MutflowFiles.parseDiscoveryJson(json)

        assertEquals(listOf(point, second), parsed.points)
        assertEquals(touchCounts, parsed.touchCounts)
    }

    @Test
    fun emptyDiscoveryJsonRoundTripsThroughParser() {
        val parsed = MutflowFiles.parseDiscoveryJson(
            MutflowFiles.buildDiscoveryJson(emptyList(), emptyMap())
        )
        assertEquals(emptyList(), parsed.points)
        assertEquals(emptyMap(), parsed.touchCounts)
    }

    @Test
    fun resultJsonRoundTripsThroughParser() {
        val json = MutflowFiles.buildResultJson(
            pointId = "sample.Calculator_0",
            variantIndex = 1,
            touched = true,
            timedOut = false
        )
        val parsed = MutflowFiles.parseResultJson(json)
        assertEquals(
            ResultFileContent(
                pointId = "sample.Calculator_0",
                variantIndex = 1,
                touched = true,
                timedOut = false
            ),
            parsed
        )
    }

    @Test
    fun parserRejectsUnknownFormatVersion() {
        val json = "{\"formatVersion\":99,\"points\":[]}"
        val e = assertFailsWith<MutflowFileFormatException> {
            MutflowFiles.parseDiscoveryJson(json)
        }
        assertTrue("formatVersion 99" in e.message!!)
    }

    @Test
    fun parserRejectsMalformedJson() {
        assertFailsWith<MutflowFileFormatException> {
            MutflowFiles.parseDiscoveryJson("{\"formatVersion\":1,\"points\":[")
        }
        assertFailsWith<MutflowFileFormatException> {
            MutflowFiles.parseResultJson("not json at all")
        }
    }

    @Test
    fun parserRejectsMissingFields() {
        val e = assertFailsWith<MutflowFileFormatException> {
            MutflowFiles.parseResultJson("{\"formatVersion\":1,\"pointId\":\"a\",\"variantIndex\":0,\"touched\":true}")
        }
        assertTrue("timedOut" in e.message!!)
    }

    @Test
    fun jsonStringEscapesQuotesBackslashesAndControlChars() {
        assertEquals("\"plain\"", MutflowFiles.jsonString("plain"))
        assertEquals("\"say \\\"hi\\\"\"", MutflowFiles.jsonString("say \"hi\""))
        assertEquals("\"back\\\\slash\"", MutflowFiles.jsonString("back\\slash"))
        assertEquals("\"line\\nbreak\"", MutflowFiles.jsonString("line\nbreak"))
        assertEquals("\"tab\\there\"", MutflowFiles.jsonString("tab\there"))
        assertEquals("\"cr\\rhere\"", MutflowFiles.jsonString("cr\rhere"))
        // A control character without a shorthand escape (U+0001).
        assertEquals("\"a\\u0001b\"", MutflowFiles.jsonString("a\u0001b"))
    }
}
