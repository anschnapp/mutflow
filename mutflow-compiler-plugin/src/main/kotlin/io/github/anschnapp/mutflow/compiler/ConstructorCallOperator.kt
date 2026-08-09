package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.makeNullable

/**
 * Mutation operator for constructor calls: replaces the constructed object with `null`.
 *
 * Mirrors pitest's `CONSTRUCTOR_CALLS` mutator. Detects tests that don't verify
 * object creation — e.g. a test that only checks the constructor doesn't throw
 * will pass even when the object is never actually built.
 *
 * The null constant uses the nullable form of the constructed type; the enclosing
 * `when` keeps the original (non-nullable) type, so an active mutant surfaces as a
 * null where a real object was expected.
 */
@OptIn(UnsafeDuringIrConstructionAPI::class)
class ConstructorCallOperator : ConstructorCallMutationOperator {

    companion object {
        // Types whose members delegate to a native/JS-backed implementation (e.g.
        // Regex's platform regex engine) where a null-deref segfaults uncatchably
        // on Kotlin/Native and Kotlin/Wasm instead of throwing a catchable NPE like
        // it does on JVM/JS. There's no reliable IR-level signal (e.g. `isExternal`
        // on a member function) that flags this — the crash can originate several
        // calls deep inside stdlib internals — so this has to be a manually
        // maintained list of FQNs found to be unsafe in practice.
        private val UNSAFE_NULL_DEREF_TYPES = setOf(
            "kotlin.text.Regex"
        )
    }

    override val descriptor = MutatorDescriptor(
        id = "CONSTRUCTOR_CALL",
        name = "ConstructorCall",
        description = "Replace constructor call with null",
        group = MutatorGroup.CALL,
        status = MutatorStatus.STABLE
    )

    override fun matches(call: IrConstructorCall): Boolean =
        call.type.classFqName?.asString() !in UNSAFE_NULL_DEREF_TYPES

    override fun originalDescription(call: IrConstructorCall): String =
        call.symbol.owner.name.asString()

    override fun variants(call: IrConstructorCall, context: MutationContext): List<MutationOperator.Variant> {
        val nullableType = call.type.makeNullable()
        return listOf(
            MutationOperator.Variant("null") {
                IrConstImpl.constNull(call.startOffset, call.endOffset, nullableType)
            }
        )
    }
}
