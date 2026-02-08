package io.github.anschnapp.mutflow

/**
 * Marks a class as a target for mutation injection.
 *
 * The compiler plugin will only inject mutations into classes
 * annotated with this annotation. This limits bytecode bloat
 * and keeps mutations relevant to code under test.
 *
 * Example:
 * ```kotlin
 * @MutationTarget
 * class Calculator {
 *     fun add(a: Int, b: Int): Int = a + b
 * }
 * ```
 *
 * ## Suppressing mutations
 *
 * Mutations can be suppressed at different granularities:
 *
 * - **Class or function level:** Use [@SuppressMutations] to skip an entire class or function.
 * - **Line level:** Use `// mutflow:ignore` or `// mutflow:falsePositive` comments to skip
 *   mutations on a single line. Both keywords have the same technical effect but communicate
 *   different intent:
 *   - `mutflow:ignore` — the code is not worth testing (logging, debug utilities, heuristics)
 *   - `mutflow:falsePositive` — the mutation is an equivalent mutant or not meaningful to test
 *
 * Free-form text after the keyword serves as documentation for reviewers.
 *
 * **Inline comment** — suppresses mutations on the same line:
 * ```kotlin
 * val threshold = x > 100 // mutflow:ignore this is just a heuristic threshold
 * ```
 *
 * **Standalone comment** — suppresses mutations on the next line:
 * ```kotlin
 * // mutflow:falsePositive equivalent mutant, >= and > both valid here
 * if (retryCount > MAX_RETRIES) { ... }
 * ```
 *
 * Comment-based suppression has zero production overhead — comments are stripped by the
 * compiler and no runtime artifacts are added. The compiler plugin reads the source file
 * during IR transformation to detect these comments.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MutationTarget
