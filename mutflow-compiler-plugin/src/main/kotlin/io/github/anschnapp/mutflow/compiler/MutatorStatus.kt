package io.github.anschnapp.mutflow.compiler

/**
 * Stability of a mutation operator.
 *
 * - [STABLE]: well-understood, low-noise operators that are safe to enable by default.
 * - [EXPERIMENTAL]: operators that are high-noise, imprecise, or not yet validated
 *   across all backends. Must be opted into explicitly.
 */
enum class MutatorStatus {
    STABLE,
    EXPERIMENTAL
}
