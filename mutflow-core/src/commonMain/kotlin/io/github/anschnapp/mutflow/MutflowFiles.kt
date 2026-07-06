package io.github.anschnapp.mutflow

/**
 * Serialization of the discovery/result files used by the Native
 * orchestration path (DESIGN-KOTLIN-NATIVE.md).
 *
 * On Native, one process = one run: the Gradle orchestrator launches the test
 * binary once for discovery and once per mutation, and these files are how
 * the binary reports back across the process boundary (the JVM path never
 * needs them - everything stays in memory there).
 *
 * The JSON is hand-rolled on purpose: mutflow's runtime has zero external
 * dependencies today, and pulling in kotlinx.serialization just to write two
 * small fixed-schema files (parsed later by the Gradle plugin on the JVM,
 * Phase 3) would be a heavy trade. The builders are pure String functions so
 * they can be unit-tested in commonTest without any file IO.
 *
 * Both formats carry a formatVersion so the Phase 3 parser can detect a
 * mismatch between the plugin and runtime versions instead of misreading.
 */
object MutflowFiles {

    /** Bumped whenever the schema of either file changes incompatibly. */
    const val FORMAT_VERSION: Int = 1

    /**
     * Builds the discovery file content.
     *
     * @param points All mutation points discovered during the baseline run,
     *   in discovery order
     * @param touchCounts How many `underTest {}` blocks touched each point
     *   (same meaning as the JVM baseline's touch counts; feeds the
     *   MostLikely* selection strategies in the Gradle orchestrator)
     */
    fun buildDiscoveryJson(
        points: List<DiscoveredPoint>,
        touchCounts: Map<String, Int>
    ): String = buildString {
        append("{\"formatVersion\":").append(FORMAT_VERSION)
        append(",\"points\":[")
        for ((index, point) in points.withIndex()) {
            if (index > 0) append(',')
            // One point per line keeps the file diffable/readable when
            // debugging an orchestration run by hand.
            append('\n')
            append("{\"pointId\":").append(jsonString(point.pointId))
            append(",\"variantCount\":").append(point.variantCount)
            append(",\"sourceLocation\":").append(jsonString(point.sourceLocation))
            append(",\"originalOperator\":").append(jsonString(point.originalOperator))
            append(",\"variantOperators\":[")
            for ((opIndex, op) in point.variantOperators.withIndex()) {
                if (opIndex > 0) append(',')
                append(jsonString(op))
            }
            append(']')
            append(",\"occurrenceOnLine\":").append(point.occurrenceOnLine)
            append(",\"touchCount\":").append(touchCounts[point.pointId] ?: 0)
            append('}')
        }
        if (points.isNotEmpty()) append('\n')
        append("]}")
        append('\n')
    }

    /**
     * Builds the per-mutation result file content.
     *
     * The mutation verdict itself (killed/survived) is decided by the Gradle
     * orchestrator from the process exit code; this file adds what the exit
     * code cannot express:
     *
     * @param touched Whether the active mutation point was reached at all -
     *   lets the orchestrator distinguish "survived" from "mutation never
     *   executed" (which usually points at a test-selection problem)
     * @param timedOut Whether a run hit the mutation timeout (likely an
     *   infinite loop caused by the mutation); the process still exits
     *   nonzero, but the orchestrator can report it as TIMED_OUT instead
     *   of KILLED
     */
    fun buildResultJson(
        pointId: String,
        variantIndex: Int,
        touched: Boolean,
        timedOut: Boolean
    ): String = buildString {
        append("{\"formatVersion\":").append(FORMAT_VERSION)
        append(",\"pointId\":").append(jsonString(pointId))
        append(",\"variantIndex\":").append(variantIndex)
        append(",\"touched\":").append(touched)
        append(",\"timedOut\":").append(timedOut)
        append("}\n")
    }

    /** Builds and writes the discovery file (overwrites any existing file). */
    fun writeDiscoveryFile(
        path: String,
        points: List<DiscoveredPoint>,
        touchCounts: Map<String, Int>
    ) {
        writeTextFile(path, buildDiscoveryJson(points, touchCounts))
    }

    /** Builds and writes the result file (overwrites any existing file). */
    fun writeResultFile(
        path: String,
        pointId: String,
        variantIndex: Int,
        touched: Boolean,
        timedOut: Boolean
    ) {
        writeTextFile(path, buildResultJson(pointId, variantIndex, touched, timedOut))
    }

    /**
     * Encodes [value] as a JSON string literal, including the surrounding
     * quotes. Escapes the two mandatory characters (`"` and `\`) plus all
     * control characters below U+0020, per the JSON spec. Operator strings
     * are benign today, but source locations contain user file names, so
     * correctness here is not optional.
     */
    internal fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> {
                    // Remaining control chars as \u00XX (code is always < 0x20,
                    // so two hex digits padded to four).
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                }
                else -> append(ch)
            }
        }
        append('"')
    }

    // ==================== Parsing (Gradle orchestrator side) ====================
    //
    // The parse functions are the read counterparts of the builders above.
    // They live in the same object on purpose: writer and reader must agree on
    // the format, and keeping them together lets commonTest round-trip them on
    // every target. At runtime only the Gradle plugin (JVM) calls them - the
    // native test binary only ever writes.

    /**
     * Parses a discovery file produced by [buildDiscoveryJson].
     *
     * @throws MutflowFileFormatException if the content is not valid JSON, is
     *   missing fields, or was written by an incompatible runtime version
     */
    fun parseDiscoveryJson(text: String): DiscoveryFileContent {
        val root = parseRootObject(text, "discovery")
        checkFormatVersion(root, "discovery")

        val rawPoints = root["points"] as? List<*>
            ?: throw MutflowFileFormatException("Discovery file has no \"points\" array")

        val points = mutableListOf<DiscoveredPoint>()
        val touchCounts = mutableMapOf<String, Int>()
        for (raw in rawPoints) {
            val obj = raw as? Map<*, *>
                ?: throw MutflowFileFormatException("Discovery file: entry in \"points\" is not an object")
            val pointId = obj.stringField("pointId")
            points.add(
                DiscoveredPoint(
                    pointId = pointId,
                    variantCount = obj.intField("variantCount"),
                    sourceLocation = obj.stringField("sourceLocation"),
                    originalOperator = obj.stringField("originalOperator"),
                    variantOperators = obj.stringListField("variantOperators"),
                    occurrenceOnLine = obj.intField("occurrenceOnLine")
                )
            )
            touchCounts[pointId] = obj.intField("touchCount")
        }
        return DiscoveryFileContent(points = points, touchCounts = touchCounts)
    }

    /**
     * Parses a result file produced by [buildResultJson].
     *
     * @throws MutflowFileFormatException if the content is not valid JSON, is
     *   missing fields, or was written by an incompatible runtime version
     */
    fun parseResultJson(text: String): ResultFileContent {
        val root = parseRootObject(text, "result")
        checkFormatVersion(root, "result")
        return ResultFileContent(
            pointId = root.stringField("pointId"),
            variantIndex = root.intField("variantIndex"),
            touched = root.booleanField("touched"),
            timedOut = root.booleanField("timedOut")
        )
    }

    private fun parseRootObject(text: String, fileKind: String): Map<*, *> {
        val value = try {
            JsonReader(text).readSingleValue()
        } catch (e: MutflowFileFormatException) {
            throw MutflowFileFormatException("Malformed $fileKind file: ${e.message}")
        }
        return value as? Map<*, *>
            ?: throw MutflowFileFormatException("Malformed $fileKind file: top-level value is not an object")
    }

    private fun checkFormatVersion(root: Map<*, *>, fileKind: String) {
        val version = root.intField("formatVersion")
        if (version != FORMAT_VERSION) {
            throw MutflowFileFormatException(
                "The $fileKind file has formatVersion $version but this mutflow version expects $FORMAT_VERSION. " +
                    "The mutflow Gradle plugin and the mutflow runtime linked into the test binary " +
                    "are probably different versions - align them and rebuild."
            )
        }
    }

    private fun Map<*, *>.field(name: String): Any =
        get(name) ?: throw MutflowFileFormatException("Missing field \"$name\"")

    private fun Map<*, *>.stringField(name: String): String =
        field(name) as? String
            ?: throw MutflowFileFormatException("Field \"$name\" is not a string")

    private fun Map<*, *>.intField(name: String): Int =
        (field(name) as? Long)?.let {
            if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt()
            else throw MutflowFileFormatException("Field \"$name\" is out of Int range")
        } ?: throw MutflowFileFormatException("Field \"$name\" is not an integer")

    private fun Map<*, *>.booleanField(name: String): Boolean =
        field(name) as? Boolean
            ?: throw MutflowFileFormatException("Field \"$name\" is not a boolean")

    private fun Map<*, *>.stringListField(name: String): List<String> {
        val list = field(name) as? List<*>
            ?: throw MutflowFileFormatException("Field \"$name\" is not an array")
        return list.map {
            it as? String ?: throw MutflowFileFormatException("Field \"$name\" contains a non-string entry")
        }
    }
}

/**
 * Parsed content of a discovery file. The formatVersion is validated during
 * parsing and therefore not carried here.
 */
data class DiscoveryFileContent(
    val points: List<DiscoveredPoint>,
    val touchCounts: Map<String, Int>
)

/** Parsed content of a per-mutation result file. */
data class ResultFileContent(
    val pointId: String,
    val variantIndex: Int,
    val touched: Boolean,
    val timedOut: Boolean
)

/**
 * Thrown when a discovery/result file cannot be parsed - either because it is
 * corrupt or because plugin and runtime disagree on the format version.
 */
class MutflowFileFormatException(message: String) : RuntimeException(message)

/**
 * Minimal recursive-descent JSON reader, just enough for the fixed-schema
 * files this object writes (objects, arrays, strings, integers, booleans,
 * null). Hand-rolled for the same zero-dependency reason as the writer side.
 *
 * Integers surface as [Long]; the typed field accessors above narrow them.
 */
private class JsonReader(private val text: String) {
    private var pos = 0

    fun readSingleValue(): Any? {
        val value = readValue()
        skipWhitespace()
        if (pos != text.length) fail("trailing content after JSON value")
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (pos >= text.length) fail("unexpected end of input")
        return when (val ch = text[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            else -> if (ch == '-' || ch.isDigit()) readNumber() else fail("unexpected character '$ch'")
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val result = mutableMapOf<String, Any?>()
        skipWhitespace()
        if (peek() == '}') { pos++; return result }
        while (true) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            result[key] = readValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return result }
                else -> fail("expected ',' or '}' in object")
            }
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        val result = mutableListOf<Any?>()
        skipWhitespace()
        if (peek() == ']') { pos++; return result }
        while (true) {
            result.add(readValue())
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return result }
                else -> fail("expected ',' or ']' in array")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            when (val ch = text[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) fail("unterminated escape")
                    when (val esc = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > text.length) fail("truncated \\u escape")
                            val hex = text.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: fail("invalid \\u escape '$hex'")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("invalid escape '\\$esc'")
                    }
                }
                else -> sb.append(ch)
            }
        }
    }

    private fun readNumber(): Long {
        val start = pos
        if (peek() == '-') pos++
        while (pos < text.length && text[pos].isDigit()) pos++
        val raw = text.substring(start, pos)
        // The files only ever contain integers; anything else (fractions,
        // exponents) is a format error, not a value to silently truncate.
        return raw.toLongOrNull() ?: fail("invalid number '$raw'")
    }

    private fun <T> readLiteral(literal: String, value: T): T {
        if (!text.startsWith(literal, pos)) fail("invalid literal at position $pos")
        pos += literal.length
        return value
    }

    private fun peek(): Char {
        if (pos >= text.length) fail("unexpected end of input")
        return text[pos]
    }

    private fun expect(ch: Char) {
        if (peek() != ch) fail("expected '$ch' but found '${text[pos]}'")
        pos++
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++
    }

    private fun fail(message: String): Nothing =
        throw MutflowFileFormatException("$message (at offset $pos)")
}
