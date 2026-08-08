package io.github.anschnapp.mutflow.compiler

/**
 * Category of a mutation operator, mirroring the KMR operator catalog
 * (`docs/mutation-catalog.md`). Used for grouping, filtering, and reporting.
 */
enum class MutatorGroup(val displayName: String) {
    RELATIONAL("Relational"),
    ARITHMETIC("Arithmetic"),
    BOOLEAN("Boolean"),
    CONSTANT("Constant"),
    RETURN("Return"),
    CALL("Call"),
    CONTROL_FLOW("Control Flow"),
    KOTLIN_SPECIFIC("Kotlin-specific"),
    STRING("String"),
    COLLECTION("Collection"),
    REGEX("Regex")
}
