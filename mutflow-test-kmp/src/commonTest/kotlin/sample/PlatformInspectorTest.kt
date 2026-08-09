package sample

import io.github.anschnapp.mutflow.ActiveMutation
import io.github.anschnapp.mutflow.DiscoveredPoint
import io.github.anschnapp.mutflow.MutationRegistry
import kotlin.test.Test

/**
 * Multiplatform binary inspector.
 *
 * Runs the same test battery against the MUTATED Calculator on every KMP target
 * (JVM, JS, WASM, Native), discovers every mutation point, and for each point ×
 * variant records whether the mutant was killed (any input's result differed from
 * baseline) or survived. Results are serialized to JSON and written to
 * `build/inspect-results/<platform>.json` via the platform-specific [writeResultsFile].
 *
 * A separate script (`tools/mutflow-inspect/inspect-all.sh`) runs all targets and
 * aggregates the JSON files into one HTML dashboard.
 */
class PlatformInspectorTest {

    private val calc = Calculator()

    // Battery: method name -> list of input suppliers. Each input is run under the
    // baseline and under every mutant; a differing result kills the mutant.
    private val battery: Map<String, List<() -> Any?>> = mapOf(
        "add" to listOf({ calc.add(5, 3) }, { calc.add(0, 0) }, { calc.add(-2, 7) }),
        "isPositive" to listOf({ calc.isPositive(5) }, { calc.isPositive(0) }, { calc.isPositive(-3) }),
        "isInRange" to listOf({ calc.isInRange(50) }, { calc.isInRange(0) }, { calc.isInRange(200) }),
        "max" to listOf({ calc.max(3, 1) }, { calc.max(1, 3) }, { calc.max(4, 4) }),
        "startsWithA" to listOf({ calc.startsWithA("A") }, { calc.startsWithA("B") }, { calc.startsWithA("") }),
        "normalized" to listOf({ calc.normalized(" x ") }, { calc.normalized("x") }, { calc.normalized("") }),
        "sameRef" to listOf({ calc.sameRef("a", "a") }, { calc.sameRef("a", "b") }),
        "notSameRef" to listOf({ calc.notSameRef("a", "a") }, { calc.notSameRef("a", "b") }),
        "greet" to listOf({ calc.greet(null) }, { calc.greet("bob") }),
        "lengthOf" to listOf({ calc.lengthOf("abc") }, { calc.lengthOf(null) }),
        "emptyListReturn" to listOf({ calc.emptyListReturn() }),
        "doubleThenSet" to listOf({ calc.doubleThenSet(21) }, { calc.doubleThenSet(0) }),
        "switchOp" to listOf({ calc.switchOp(1) }, { calc.switchOp(2) }, { calc.switchOp(9) }),
        "combine" to listOf({ calc.combine(3, 4) }, { calc.combine(0, 0) }),
        "matchesRegex" to listOf({ calc.matchesRegex("abc") }, { calc.matchesRegex("xabc") }, { calc.matchesRegex("xyz") })
    )

    @Test
    fun inspectAllPlatforms() {
        // Baseline: discover points + record expected results per input.
        val expected = MutationRegistry.withSession<Map<String, List<Any?>>>(null) { runBattery() }.first
        val points = MutationRegistry.withSession<Unit>(null) { runBattery() }.second.discoveredPoints

        // For each point × variant, run the battery and classify killed/survived.
        val variants = mutableListOf<VariantResult>()
        for (p in points) {
            for (v in 0 until p.variantCount) {
                val mutant = MutationRegistry.withSession<Map<String, List<Any?>>>(
                    ActiveMutation(p.pointId, v)
                ) { runBattery() }.first
                val killed = differs(expected, mutant)
                variants.add(
                    VariantResult(
                        pointId = p.pointId,
                        variant = v,
                        operator = p.originalOperator,
                        location = p.sourceLocation,
                        killed = killed,
                        detail = detailLines(expected, mutant)
                    )
                )
            }
        }

        writeResultsFile(currentPlatform(), buildJson(currentPlatform(), variants))
    }

    private fun runBattery(): Map<String, List<Any?>> {
        val results = mutableMapOf<String, List<Any?>>()
        for ((name, inputs) in battery) {
            results[name] = inputs.map { input ->
                try {
                    input()
                } catch (t: Throwable) {
                    "CRASH:${t::class.simpleName}"
                }
            }
        }
        return results
    }

    private fun differs(a: Map<String, List<Any?>>, b: Map<String, List<Any?>>): Boolean {
        for ((k, la) in a) {
            val lb = b[k] ?: return true
            if (la.size != lb.size) return true
            for (i in la.indices) if (la[i] != lb[i]) return true
        }
        return false
    }

    /** Returns lines like "add[0]: 8 -> 2" for each input whose result changed. */
    private fun detailLines(a: Map<String, List<Any?>>, b: Map<String, List<Any?>>): List<String> {
        val lines = mutableListOf<String>()
        for ((k, la) in a) {
            val lb = b[k] ?: continue
            if (la.size != lb.size) continue
            for (i in la.indices) {
                if (la[i] != lb[i]) lines.add("$k[$i]: ${la[i]} -> ${lb[i]}")
            }
        }
        return lines
    }

    private fun buildJson(platform: String, variants: List<VariantResult>): String {
        val sb = StringBuilder()
        sb.append("{\"platform\":").append(jsonStr(platform)).append(",\"variants\":[")
        variants.forEachIndexed { i, v ->
            if (i > 0) sb.append(",")
            sb.append("{")
                .append("\"pointId\":").append(jsonStr(v.pointId)).append(",")
                .append("\"variant\":").append(v.variant).append(",")
                .append("\"operator\":").append(jsonStr(v.operator)).append(",")
                .append("\"location\":").append(jsonStr(v.location)).append(",")
                .append("\"killed\":").append(v.killed).append(",")
                .append("\"detail\":").append(jsonStr(v.detail.joinToString(" | ")))
                .append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    data class VariantResult(
        val pointId: String,
        val variant: Int,
        val operator: String,
        val location: String,
        val killed: Boolean,
        val detail: List<String>
    )
}

/** Platform name (e.g. "jvm", "js", "wasmJs", "linuxX64"). */
internal expect fun currentPlatform(): String

/** Writes the inspector JSON to `build/inspect-results/<platform>.json`. */
internal expect fun writeResultsFile(platform: String, json: String)
