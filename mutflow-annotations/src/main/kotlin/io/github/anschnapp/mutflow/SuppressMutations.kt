package io.github.anschnapp.mutflow

/**
 * Suppresses mutation injection for the annotated class or function.
 *
 * When applied to a class, no mutations are injected into any methods of that class.
 * When applied to a function, no mutations are injected into that function.
 *
 * Use this for code where mutation testing is not meaningful, such as logging
 * or debug utilities.
 *
 * Example:
 * ```kotlin
 * @SuppressMutations
 * fun logWarningIfNegative(value: Int) {
 *     if (value < 0) {
 *         logger.warn("Negative value: $value")
 *     }
 * }
 * ```
 *
 * For **line-level** suppression, use `// mutflow:ignore` or `// mutflow:falsePositive`
 * comments instead. See [MutationTarget] for details and examples.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class SuppressMutations
