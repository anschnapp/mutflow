package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

/**
 * Abstraction for mutation operators that target function bodies.
 *
 * Unlike [MutationOperator] (which targets individual call expressions) and
 * [ReturnMutationOperator] (which targets return statements), this interface
 * operates at the function level — wrapping the entire body in a mutation guard.
 *
 * The transformer handles the body wrapping directly. Implementations only need
 * to specify which functions to match and provide descriptions for display.
 */
interface FunctionBodyMutationOperator {

    /**
     * Returns true if this operator can generate a mutation for the given function.
     */
    fun matches(function: IrSimpleFunction): Boolean

    /**
     * Returns a description of the original function for display.
     * Example: "save()" for a void function named save.
     */
    fun originalDescription(function: IrSimpleFunction): String

    /**
     * Returns the number of mutation variants for this function.
     */
    fun variantCount(function: IrSimpleFunction): Int

    /**
     * Returns descriptions for each variant.
     * Example: ["removed"] for void function body removal.
     */
    fun variantDescriptions(function: IrSimpleFunction): List<String>
}
