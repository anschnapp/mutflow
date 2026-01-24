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
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class MutationTarget
