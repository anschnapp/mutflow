package io.github.anschnapp.mutflow

import kotlin.test.Test
import kotlin.test.assertEquals

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
