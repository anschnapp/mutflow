package io.github.anschnapp.mutflow.compiler

/**
 * Declarative metadata for a mutation operator.
 *
 * This is the single source of truth for an operator's identity, independent of
 * its IR-matching implementation. The [id] is stable across refactors and is the
 * value used in reports and for enabling/disabling operators by name.
 *
 * @property id Stable, unique identifier (e.g. `"RELATIONAL_COMPARISON"`).
 *   Must not change across refactors — it is persisted in mutation reports.
 * @property name Short display name (e.g. `"RelationalComparison"`).
 * @property description Human-readable description of the transformation.
 * @property group Category from the KMR catalog.
 * @property status Stability tier; [MutatorStatus.EXPERIMENTAL] operators are
 *   excluded from the default set.
 */
data class MutatorDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val group: MutatorGroup,
    val status: MutatorStatus
)
