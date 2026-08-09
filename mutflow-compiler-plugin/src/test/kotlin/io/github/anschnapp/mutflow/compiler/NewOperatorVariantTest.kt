package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the newly implemented experimental operators: ArgumentPropagation,
 * Switch, and RegexPattern.
 */
class NewOperatorVariantTest {

    private fun compile(source: String): IrTestCompiler.CompiledModule = IrTestCompiler.compile(source)

    private fun IrModuleFragment.collectCalls(): List<IrCall> {
        val calls = mutableListOf<IrCall>()
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                calls += expression
                super.visitCall(expression)
            }
        })
        return calls
    }

    private fun IrModuleFragment.collectWhens(): List<IrWhen> {
        val whens = mutableListOf<IrWhen>()
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitWhen(expression: IrWhen) {
                whens += expression
                super.visitWhen(expression)
            }
        })
        return whens
    }

    private fun IrModuleFragment.collectConstructorCalls(): List<IrConstructorCall> {
        val ctors = mutableListOf<IrConstructorCall>()
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                ctors += expression
                super.visitConstructorCall(expression)
            }
        })
        return ctors
    }

    // --- ArgumentPropagationOperator ---

    @Test
    fun `argument propagation on same-typed args`() {
        val compiled = compile(
            "fun g(a: Int, b: Int, c: Int): Int = a\n" +
                "fun f(x: Int, y: Int): Int = g(x, y, x)"
        )
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "g" }
        val operator = ArgumentPropagationOperator()

        assertTrue(operator.matches(call))
        // x (idx0), y (idx1), x (idx2): first two value args (idx0=x, idx1=y) are same-typed,
        // so we get "propagate x→1" and "propagate y→0".
        assertEquals(
            listOf("g(arg->1)", "g(arg->0)"),
            operator.variants(call, compiled.contextFor("f")).map { it.description }
        )
    }

    @Test
    fun `argument propagation requires two same-typed value args`() {
        // Single argument call -> no mutation.
        val compiled = compile("fun g(a: Int): Int = a\nfun f(x: Int): Int = g(x)")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "g" }
        val operator = ArgumentPropagationOperator()

        assertTrue(!operator.matches(call), "single-arg call must not match")
    }

    @Test
    fun `argument propagation skips operator calls`() {
        // Operator calls have a non-null origin and must be skipped.
        val compiled = compile("fun f(a: Int, b: Int): Int = a + b")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "plus" }
        val operator = ArgumentPropagationOperator()

        assertTrue(!operator.matches(call), "operator call must not match")
    }

    // --- SwitchOperator ---

    @Test
    fun `switch generates swap and remove variants`() {
        val compiled = compile(
            "fun f(x: Int): Int = when (x) { 1 -> 10; 2 -> 20; else -> 0 }"
        )
        val whenExpr = compiled.module.collectWhens().first { it.origin?.debugName == "WHEN" }
        val operator = SwitchOperator()

        assertTrue(operator.matches(whenExpr))
        assertEquals(
            listOf("swap first two cases", "remove first case"),
            operator.variants(whenExpr, compiled.contextFor("f")).map { it.description }
        )
    }

    @Test
    fun `switch does not match if-when`() {
        val compiled = compile("fun f(a: Boolean): Int = if (a) 1 else 2")
        val operator = SwitchOperator()
        val whens = compiled.module.collectWhens()
        assertTrue(whens.none { operator.matches(it) }, "Switch must not match an if")
    }

    // --- RegexPatternOperator ---

    @Test
    fun `regex pattern mutates anchors`() {
        val compiled = compile(
            "fun f(s: String): Boolean = Regex(\"^abc\").containsMatchIn(s)"
        )
        val ctor = compiled.module.collectConstructorCalls().first {
            it.symbol.owner.parent is org.jetbrains.kotlin.ir.declarations.IrClass
        }
        val operator = RegexPatternOperator()

        assertTrue(operator.matches(ctor))
        val variants = operator.variants(ctor, compiled.contextFor("f")).map { it.description }
        // Removing the leading ^ yields "abc".
        assertTrue(variants.any { it.contains("abc") }, "expected an anchor-removed variant, got: $variants")
    }

    @Test
    fun `regex pattern mutator only matches string literal patterns`() {
        val compiled = compile(
            "fun f(): String = Regex(\"^a\").pattern"
        )
        val operator = RegexPatternOperator()
        val ctors = compiled.module.collectConstructorCalls()
        // The pattern is a string literal so it matches.
        assertTrue(ctors.any { operator.matches(it) }, "Regex ctor with literal pattern must match")
    }

    @Test
    fun `regex pattern mutator mutatePattern rewrites`() {
        val operator = RegexPatternOperator()
        val mutated = operator.mutatePattern("^[ab]+$")
        // Leading ^ removed, trailing $ removed, class [ab]->[^ab], quantifier + removed.
        assertTrue(mutated.contains("^[^ab]+$"), "expected class-negate variant, got: $mutated")
        assertTrue(mutated.contains("^[ab]+"), "expected anchor-removed variants, got: $mutated")
        assertTrue(mutated.contains("^[ab]$"), "expected quantifier-removed variant, got: $mutated")
        assertTrue(mutated.contains("[ab]+$"), "expected leading-anchor-removed variant, got: $mutated")
    }
}
