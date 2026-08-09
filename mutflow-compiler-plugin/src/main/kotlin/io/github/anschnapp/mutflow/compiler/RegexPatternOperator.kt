package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols

/**
 * Mutation operator for `Regex` pattern strings.
 *
 * Mirrors Stryker's weapon-regex `Regex` mutator set (§3.11). The pattern is a
 * string constant passed to the `Regex(...)` constructor, which is backend-agnostic
 * (a plain string constant on JVM/JS/Native). For a pattern constant we generate
 * a small set of Level-1 mutations, applied to the *pattern string argument*:
 * - **anchor**: remove leading `^` / trailing `$`
 * - **class negate**: `[abc]` → `[^abc]`
 * - **shorthand**: `\d`↔`\D`, `\w`↔`\W`, `\s`↔`\S`
 * - **quantifier**: remove `*`, `+`, `?`, `{n,m}` following an atom
 *
 * The mutated variant rebuilds the whole `Regex(...)` constructor call with the
 * swapped pattern, keeping the enclosing `when`'s (non-null) type. The pattern
 * argument must be a literal string constant for the mutation to apply.
 *
 * Experimental: string rewriting is best-effort and can yield equivalent or
 * invalid patterns; must be opted into explicitly.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class RegexPatternOperator : ConstructorCallMutationOperator {

    override val descriptor = MutatorDescriptor(
        id = "REGEX_PATTERN",
        name = "RegexPattern",
        description = "Mutate Regex pattern string (anchors, classes, shorthand, quantifiers)",
        group = MutatorGroup.REGEX,
        status = MutatorStatus.EXPERIMENTAL
    )

    override fun matches(call: IrConstructorCall): Boolean {
        // Detect the Regex class from the constructor call's result type, which is
        // reliable across backends. The pattern must be a string literal (possibly
        // already wrapped in a schemata `when` by StringLiteralOperator in the real
        // compilation), so extract its value via [extractPattern].
        val typeFqName = call.type.classFqName?.asString()
        if (typeFqName != "kotlin.text.Regex") return false
        return extractPattern(call.arguments.getOrNull(0)) != null
    }

    override fun originalDescription(call: IrConstructorCall): String {
        val pattern = extractPattern(call.arguments.getOrNull(0))
        return "Regex(\"$pattern\")"
    }

    override fun variants(call: IrConstructorCall, context: MutationContext): List<MutationOperator.Variant> {
        val pattern = extractPattern(call.arguments.getOrNull(0)) ?: return emptyList()

        val mutations = mutatePattern(pattern)
        if (mutations.isEmpty()) return emptyList()

        val patternArg = call.arguments.getOrNull(0)
        val patternType = patternArg?.type ?: return emptyList()
        val patternOffset = patternArg.startOffset
        val patternEnd = patternArg.endOffset

        return mutations.map { mutated ->
            MutationOperator.Variant("Regex(\"$mutated\")") {
                // Rebuild the constructor call by deep-copying the original (preserving
                // its bound symbol and type arguments) and swapping the pattern argument
                // for the mutated literal.
                val newCall = call.deepCopyWithSymbols()
                newCall.arguments[0] = IrConstImpl.string(patternOffset, patternEnd, patternType, mutated)
                newCall
            }
        }
    }

    /**
     * Extracts the pattern string from a Regex constructor's first argument. Handles
     * both a direct string literal and a string literal wrapped in a schemata `when`
     * (produced by StringLiteralOperator when the real compiler plugin runs first):
     * the original const sits in the `when`'s trailing else-branch result.
     */
    private fun extractPattern(arg: IrExpression?): String? {
        val direct = (arg as? IrConst)?.value as? String
        if (direct != null) return direct
        val whenExpr = arg as? org.jetbrains.kotlin.ir.expressions.IrWhen ?: return null
        // Trailing branch is the else; recurse into its result to find the original const.
        val last = whenExpr.branches.lastOrNull() ?: return null
        return extractPattern(last.result)
    }

    /**
     * Returns the set of mutated pattern strings. Best-effort string rewriting;
     * identical patterns are dropped.
     */
    internal fun mutatePattern(pattern: String): List<String> {
        val out = mutableSetOf<String>()

        // Anchor: remove leading ^ / trailing $.
        if (pattern.startsWith("^")) out += pattern.removePrefix("^")
        if (pattern.endsWith("$")) out += pattern.removeSuffix("$")

        // Shorthand: \d↔\D, \w↔\W, \s↔\S (both directions, first occurrence).
        for ((from, to) in listOf(
            "\\d" to "\\D", "\\D" to "\\d",
            "\\w" to "\\W", "\\W" to "\\w",
            "\\s" to "\\S", "\\S" to "\\s"
        )) {
            val idx = pattern.indexOf(from)
            if (idx >= 0) out += pattern.replaceRange(idx, idx + from.length, to)
        }

        // Char class: [abc] → [^abc] (first occurrence). Insert `^` right after `[`.
        val open = pattern.indexOf('[')
        if (open >= 0) {
            val close = pattern.indexOf(']', open)
            if (close > open) {
                val cls = pattern.substring(open + 1, close)
                if (!cls.startsWith("^")) {
                    out += pattern.replaceRange(open + 1, open + 1, "^")
                }
            }
        }

        // Quantifier: remove * / + / ? / {n,m} following an atom (first occurrence).
        for (i in pattern.indices) {
            val c = pattern[i]
            if (c == '*' || c == '+' || c == '?') {
                out += pattern.removeRange(i, i + 1)
                break
            }
            if (c == '{') {
                val closeBrace = pattern.indexOf('}', i)
                if (closeBrace > i) {
                    out += pattern.removeRange(i, closeBrace + 1)
                    break
                }
            }
        }

        return out.toList()
    }
}
