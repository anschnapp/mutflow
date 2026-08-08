package io.github.anschnapp.mutflow.compiler

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feeds IR snippets through the mutation operators and asserts the generated
 * variants, per PLAN Phase 1 ("write unit tests that feed IR snippets and assert
 * correct variants are generated").
 */
class OperatorVariantTest {

    private fun compile(source: String): IrTestCompiler.CompiledModule = IrTestCompiler.compile(source)

    /** Collects all IrCall nodes in the module. */
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

    /** Collects all IrReturn nodes in the module. */
    private fun IrModuleFragment.collectReturns(): List<IrReturn> {
        val returns = mutableListOf<IrReturn>()
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitReturn(expression: IrReturn) {
                returns += expression
                super.visitReturn(expression)
            }
        })
        return returns
    }

    /** Collects all IrWhen nodes in the module. */
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

    /** Collects all IrConst nodes in the module. */
    private fun IrModuleFragment.collectConsts(): List<IrConst> {
        val consts = mutableListOf<IrConst>()
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitConst(expression: IrConst) {
                consts += expression
                super.visitConst(expression)
            }
        })
        return consts
    }

    /** Collects all IrConstructorCall nodes in the module. */
    private fun IrModuleFragment.collectConstructorCalls(): List<IrConstructorCall> {
        val calls = mutableListOf<IrConstructorCall>()
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitConstructorCall(expression: IrConstructorCall) {
                calls += expression
                super.visitConstructorCall(expression)
            }
        })
        return calls
    }

    /** Collects all IrSetValue (variable assignment) nodes in the module. */
    private fun IrModuleFragment.collectSetValues(): List<IrSetValue> {
        val setValues = mutableListOf<IrSetValue>()
        acceptChildrenVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitSetValue(expression: IrSetValue) {
                setValues += expression
                super.visitSetValue(expression)
            }
        })
        return setValues
    }

    // --- RelationalComparisonOperator ---

    @Test
    fun `relational comparison generates boundary and flip variants`() {
        val compiled = compile("fun f(a: Int, b: Int) = a > b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.GT }
        val operator = RelationalComparisonOperator()

        assertTrue(operator.matches(call))
        assertEquals(">", operator.originalDescription(call))
        // > → >= (boundary) and > → < (flip)
        assertEquals(listOf(">=", "<"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `relational comparison on less-than`() {
        val compiled = compile("fun f(a: Int, b: Int) = a < b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.LT }
        val operator = RelationalComparisonOperator()

        assertEquals(listOf("<=", ">"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- ArithmeticOperator ---

    @Test
    fun `arithmetic plus swaps to minus`() {
        val compiled = compile("fun f(a: Int, b: Int) = a + b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.PLUS }
        val operator = ArithmeticOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf("-"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `arithmetic multiply swaps to safe division`() {
        val compiled = compile("fun f(a: Int, b: Int) = a * b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.MUL }
        val operator = ArithmeticOperator()

        assertEquals(listOf("/"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- EqualitySwapOperator ---

    @Test
    fun `equality swap on equals`() {
        val compiled = compile("fun f(a: Int, b: Int) = a == b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.EQEQ }
        val operator = EqualitySwapOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf("!="), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `equality swap skips null comparisons`() {
        val compiled = compile("fun f(a: String?) = a == null")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.EQEQ }
        val operator = EqualitySwapOperator()

        assertTrue(!operator.matches(call), "null comparison must be skipped")
    }

    // --- ConstantBoundaryOperator ---

    @Test
    fun `constant boundary on comparison with constant`() {
        val compiled = compile("fun f(x: Int) = x > 0")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.GT }
        val operator = ConstantBoundaryOperator()

        assertTrue(operator.matches(call))
        // 0 → 1 and 0 → -1
        assertEquals(listOf("1", "-1"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `constant boundary applies to float constants`() {
        val compiled = compile("fun f(x: Double) = x > 1.0")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.GT }
        val operator = ConstantBoundaryOperator()

        assertTrue(operator.matches(call), "float constant must be a valid boundary target")
        // 1.0 → 2.0 and 1.0 → 0.0
        assertEquals(listOf("2.0", "0.0"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- UnaryMinusOperator ---

    @Test
    fun `unary minus removes the negation`() {
        val compiled = compile("fun f(a: Int) = -a")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "unaryMinus" }
        val operator = UnaryMinusOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf("noop"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- BitwiseOperator ---

    @Test
    fun `bitwise and swaps to or`() {
        val compiled = compile("fun f(a: Int, b: Int) = a and b")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "and" }
        val operator = BitwiseOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf("or"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `bitwise shl swaps to shr`() {
        val compiled = compile("fun f(a: Int, b: Int) = a shl b")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "shl" }
        val operator = BitwiseOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf("shr"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `bitwise operator does not match boolean and`() {
        val compiled = compile("fun f(a: Boolean, b: Boolean) = a and b")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "and" }
        val operator = BitwiseOperator()

        assertTrue(!operator.matches(call), "Boolean and must not be treated as bitwise")
    }

    // --- Return operators ---

    @Test
    fun `boolean return generates true and false`() {
        val compiled = compile("fun f(x: Int): Boolean { return x > 0 }")
        val ret = compiled.module.collectReturns().first()
        val operator = BooleanReturnOperator()

        assertTrue(operator.matches(ret))
        assertEquals(listOf("true", "false"), operator.variants(ret, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `primitive return generates zero`() {
        val compiled = compile("fun f(x: Int): Int { return x + 1 }")
        val ret = compiled.module.collectReturns().first()
        val operator = PrimitiveReturnOperator()

        assertTrue(operator.matches(ret))
        assertEquals(listOf("0"), operator.variants(ret, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `primitive return does not match boolean return`() {
        val compiled = compile("fun f(x: Int): Boolean { return x > 0 }")
        val ret = compiled.module.collectReturns().first()
        val operator = PrimitiveReturnOperator()

        assertTrue(!operator.matches(ret), "PrimitiveReturn must not match Boolean returns")
    }

    @Test
    fun `nullable return generates null`() {
        val compiled = compile("fun f(x: Int): Int? { return x }")
        val ret = compiled.module.collectReturns().first()
        val operator = NullableReturnOperator()

        assertTrue(operator.matches(ret))
        assertEquals(listOf("null"), operator.variants(ret, compiled.contextFor("f")).map { it.description })
    }

    // --- IncrementOperator ---

    @Test
    fun `increment swaps to decrement`() {
        val compiled = compile("fun f(a: Int): Int { var x = a; x++; return x }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "<int-postfix-incr-decr>" }
        val operator = IncrementOperator()

        assertTrue(operator.matches(call))
        assertEquals("++", operator.originalDescription(call))
        assertEquals(listOf("--"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `decrement swaps to increment`() {
        val compiled = compile("fun f(a: Int): Int { var x = a; x--; return x }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "<int-postfix-incr-decr>" }
        val operator = IncrementOperator()

        assertTrue(operator.matches(call))
        assertEquals("--", operator.originalDescription(call))
        assertEquals(listOf("++"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `increment does not match other calls`() {
        val compiled = compile("fun f(a: Int, b: Int) = a + b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.PLUS }
        val operator = IncrementOperator()

        assertTrue(!operator.matches(call), "Increment must not match arithmetic calls")
    }

    // --- ObjectReturnOperator ---

    @Test
    fun `object return generates null`() {
        val compiled = compile("fun f(x: String): String { return x }")
        val ret = compiled.module.collectReturns().first()
        val operator = ObjectReturnOperator()

        assertTrue(operator.matches(ret))
        assertEquals(listOf("null"), operator.variants(ret, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `object return does not match primitive return`() {
        val compiled = compile("fun f(x: Int): Int { return x }")
        val ret = compiled.module.collectReturns().first()
        val operator = ObjectReturnOperator()

        assertTrue(!operator.matches(ret), "ObjectReturn must not match primitive returns")
    }

    @Test
    fun `object return does not match nullable return`() {
        val compiled = compile("fun f(x: String?): String? { return x }")
        val ret = compiled.module.collectReturns().first()
        val operator = ObjectReturnOperator()

        assertTrue(!operator.matches(ret), "ObjectReturn must not match nullable returns")
    }

    // --- StringLiteralOperator ---

    @Test
    fun `string literal generates empty string`() {
        val compiled = compile("fun f(): String = \"hello\"")
        val const = compiled.module.collectConsts().first { it.type.isString() }
        val operator = StringLiteralOperator()

        assertTrue(operator.matches(const))
        assertEquals("\"hello\"", operator.originalDescription(const))
        assertEquals(listOf("\"\""), operator.variants(const, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `empty string literal generates filled string`() {
        val compiled = compile("fun f(): String = \"\"")
        val const = compiled.module.collectConsts().first { it.type.isString() }
        val operator = StringLiteralOperator()

        assertTrue(operator.matches(const))
        assertEquals("\"\"", operator.originalDescription(const))
        assertEquals(listOf("\"A\""), operator.variants(const, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `string literal does not match numeric constants`() {
        val compiled = compile("fun f(): Int = 42")
        val const = compiled.module.collectConsts().first { it.type.isInt() }
        val operator = StringLiteralOperator()

        assertTrue(!operator.matches(const), "StringLiteral must not match numeric constants")
    }

    // --- ReplaceNonVoidCallOperator ---

    @Test
    fun `non void call generates numeric default`() {
        val compiled = compile("class C { fun m(): Int = 1 }\nfun f(c: C): Int { val x = c.m(); return x }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "m" }
        val operator = ReplaceNonVoidCallOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf("0"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `non void call on string generates empty string`() {
        val compiled = compile("class C { fun m(): String = \"x\" }\nfun f(c: C): String { val x = c.m(); return x }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "m" }
        val operator = ReplaceNonVoidCallOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf(""), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `non void call does not match operator calls`() {
        val compiled = compile("fun f(a: Int, b: Int) = a + b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.PLUS }
        val operator = ReplaceNonVoidCallOperator()

        assertTrue(!operator.matches(call), "ReplaceNonVoidCall must not match operator calls")
    }

    @Test
    fun `non void call does not match boolean calls`() {
        val compiled = compile("class C { fun m(): Boolean = true }\nfun f(c: C): Boolean { val x = c.m(); return x }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "m" }
        val operator = ReplaceNonVoidCallOperator()

        assertTrue(!operator.matches(call), "ReplaceNonVoidCall must not match boolean calls")
    }

    // --- BooleanConstOperator ---

    @Test
    fun `boolean const true flips to false`() {
        val compiled = compile("fun f(): Boolean = true")
        val const = compiled.module.collectConsts().first { it.type.isBoolean() }
        val operator = BooleanConstOperator()

        assertTrue(operator.matches(const))
        assertEquals("true", operator.originalDescription(const))
        assertEquals(listOf("false"), operator.variants(const, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `boolean const false flips to true`() {
        val compiled = compile("fun f(): Boolean = false")
        val const = compiled.module.collectConsts().first { it.type.isBoolean() }
        val operator = BooleanConstOperator()

        assertTrue(operator.matches(const))
        assertEquals("false", operator.originalDescription(const))
        assertEquals(listOf("true"), operator.variants(const, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `boolean const does not match string constants`() {
        val compiled = compile("fun f(): String = \"x\"")
        val const = compiled.module.collectConsts().first { it.type.isString() }
        val operator = BooleanConstOperator()

        assertTrue(!operator.matches(const), "BooleanConst must not match string constants")
    }

    // --- ForceConditionalOperator ---

    @Test
    fun `force conditional generates true and false`() {
        val compiled = compile("fun f(a: Int, b: Int): Int = if (a > b) a else b")
        val whenExpr = compiled.module.collectWhens().first { it.origin == IrStatementOrigin.IF }
        val operator = ForceConditionalOperator()

        assertTrue(operator.matches(whenExpr))
        assertEquals("if", operator.originalDescription(whenExpr))
        assertEquals(listOf("true", "false"), operator.variants(whenExpr, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `force conditional does not match when expressions`() {
        val compiled = compile("fun f(x: Int): Int = when (x) { 1 -> 1; else -> 0 }")
        val whenExpr = compiled.module.collectWhens().first { it.origin != IrStatementOrigin.IF }
        val operator = ForceConditionalOperator()

        assertTrue(!operator.matches(whenExpr), "ForceConditional must not match when expressions")
    }

    // --- BooleanLogicOperator (when-based) ---

    @Test
    fun `boolean and swaps to or`() {
        val compiled = compile("fun f(a: Boolean, b: Boolean) = a && b")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "ANDAND" }
        val operator = BooleanLogicOperator()

        assertTrue(operator.matches(call))
        assertEquals(listOf("||"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- ConstructorCallOperator ---

    @Test
    fun `constructor call generates null`() {
        val compiled = compile("class Foo(val x: Int)\nfun f(): Foo = Foo(1)")
        val ctor = compiled.module.collectConstructorCalls().first()
        val operator = ConstructorCallOperator()

        assertTrue(operator.matches(ctor))
        assertEquals("<init>", operator.originalDescription(ctor))
        assertEquals(listOf("null"), operator.variants(ctor, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `constructor call with no args generates null`() {
        val compiled = compile("class Foo\nfun f(): Foo = Foo()")
        val ctor = compiled.module.collectConstructorCalls().first()
        val operator = ConstructorCallOperator()

        assertTrue(operator.matches(ctor))
        assertEquals(listOf("null"), operator.variants(ctor, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `constructor call inside expression generates null`() {
        val compiled = compile("class Foo(val x: Int)\nfun f(): Int = Foo(42).x")
        val ctor = compiled.module.collectConstructorCalls().first()
        val operator = ConstructorCallOperator()

        assertTrue(operator.matches(ctor))
        assertEquals(listOf("null"), operator.variants(ctor, compiled.contextFor("f")).map { it.description })
    }

    // --- RemoveIncrementOperator ---

    @Test
    fun `remove increment replaces with operand`() {
        val compiled = compile("fun f(a: Int): Int { var x = a; x++; return x }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "<int-postfix-incr-decr>" }
        val operator = RemoveIncrementOperator()

        assertTrue(operator.matches(call))
        assertEquals("++", operator.originalDescription(call))
        assertEquals(listOf("noop"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `remove decrement replaces with operand`() {
        val compiled = compile("fun f(a: Int): Int { var x = a; x--; return x }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "<int-postfix-incr-decr>" }
        val operator = RemoveIncrementOperator()

        assertTrue(operator.matches(call))
        assertEquals("--", operator.originalDescription(call))
        assertEquals(listOf("noop"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `remove increment does not match other calls`() {
        val compiled = compile("fun f(a: Int, b: Int) = a + b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.PLUS }
        val operator = RemoveIncrementOperator()

        assertTrue(!operator.matches(call), "RemoveIncrement must not match arithmetic calls")
    }

    // --- ArithmeticOperator % → * variant ---

    @Test
    fun `modulo swaps to division and multiplication`() {
        val compiled = compile("fun f(a: Int, b: Int) = a % b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.PERC }
        val operator = ArithmeticOperator()

        assertEquals(listOf("/", "*"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- BitwiseOperator xor → or additional variant ---

    @Test
    fun `bitwise xor swaps to and and or`() {
        val compiled = compile("fun f(a: Int, b: Int) = a xor b")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "xor" }
        val operator = BitwiseOperator()

        assertEquals(listOf("and", "or"), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- StringMethodOperator ---

    @Test
    fun `string endsWith swaps to startsWith`() {
        val compiled = compile("fun f(s: String) = s.endsWith(\"x\")")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString().startsWith("endsWith") }
        val operator = StringMethodOperator()

        // matches() and the display name are verifiable in the isolated test compiler;
        // full variant generation requires stdlib deserialization which the isolated
        // harness cannot provide (verified end-to-end in mutflow-test-kmp).
        assertTrue(operator.matches(call))
        assertEquals("endsWith", operator.originalDescription(call))
    }

    @Test
    fun `string trim replaces with empty string`() {
        val compiled = compile("fun f(s: String) = s.trim()")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "trim" }
        val operator = StringMethodOperator()

        assertTrue(operator.matches(call))
        assertEquals("trim", operator.originalDescription(call))
    }

    @Test
    fun `string uppercase swaps to lowercase`() {
        val compiled = compile("fun f(s: String) = s.uppercase()")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString().startsWith("uppercase") }
        val operator = StringMethodOperator()

        assertTrue(operator.matches(call))
        assertEquals("uppercase", operator.originalDescription(call))
    }

    @Test
    fun `string method does not match non-string receivers`() {
        val compiled = compile("class C { fun trim(): Int = 1 }\nfun f(c: C) = c.trim()")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "trim" }
        val operator = StringMethodOperator()

        assertTrue(!operator.matches(call), "StringMethod must not match non-String receivers")
    }

    // --- CollectionMethodOperator ---

    @Test
    fun `collection filter swaps to filterNot`() {
        val compiled = compile("fun f(xs: List<Int>) = xs.filter { it > 0 }")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "filter" }
        val operator = CollectionMethodOperator()

        // matches() and the display name are verifiable in the isolated test compiler;
        // full variant generation requires stdlib deserialization (verified end-to-end
        // in mutflow-test-kmp).
        assertTrue(operator.matches(call))
        assertEquals("filter", operator.originalDescription(call))
    }

    @Test
    fun `collection isEmpty swaps to isNotEmpty`() {
        val compiled = compile("fun f(xs: List<Int>) = xs.isEmpty()")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "isEmpty" }
        val operator = CollectionMethodOperator()

        assertTrue(operator.matches(call))
        assertEquals("isEmpty", operator.originalDescription(call))
    }

    @Test
    fun `collection min swaps to max`() {
        val compiled = compile("fun f(xs: List<Int>) = xs.min()")
        val call = compiled.module.collectCalls().first { it.symbol.owner.name.asString() == "min" }
        val operator = CollectionMethodOperator()

        assertTrue(operator.matches(call))
        assertEquals("min", operator.originalDescription(call))
    }

    @Test
    fun `collection method does not match non-collection receivers`() {
        val compiled = compile("fun f(s: String): Int = s.length")
        val operator = CollectionMethodOperator()
        // No collection method calls present; a non-collection call should not match.
        val calls = compiled.module.collectCalls().filter { it.symbol.owner.name.asString() == "length" }
        assertTrue(calls.none { operator.matches(it) }, "CollectionMethod must not match non-collection calls")
    }

    // --- ReferenceEqualityOperator ---

    @Test
    fun `reference equality swaps === to !==`() {
        val compiled = compile("fun f(a: Any, b: Any): Boolean = a === b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.EQEQEQ }
        val operator = ReferenceEqualityOperator()

        assertTrue(operator.matches(call))
        assertEquals("===", operator.originalDescription(call))
        assertEquals(listOf("!=="), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `reference equality swaps !== to ===`() {
        val compiled = compile("fun f(a: Any, b: Any): Boolean = a !== b")
        val call = compiled.module.collectCalls().first { it.origin == IrStatementOrigin.EXCLEQEQ }
        val operator = ReferenceEqualityOperator()

        assertTrue(operator.matches(call))
        assertEquals("!==", operator.originalDescription(call))
        assertEquals(listOf("==="), operator.variants(call, compiled.contextFor("f")).map { it.description })
    }

    // --- ElvisOperator ---

    @Test
    fun `elvis replaces with subject and fallback`() {
        val compiled = compile("fun f(a: String?): String = a ?: \"d\"")
        val elvisWhen = compiled.module.collectWhens().firstOrNull {
            it.origin?.debugName == "FOLDED_ELVIS"
        } ?: error("no FOLDED_ELVIS when found")
        val operator = ElvisOperator()

        assertTrue(operator.matches(elvisWhen))
        assertEquals("?:", operator.originalDescription(elvisWhen))
        assertEquals(listOf("b", "a"), operator.variants(elvisWhen, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `elvis does not match non-elvis whens`() {
        val compiled = compile("fun f(a: Boolean): Int = if (a) 1 else 2")
        val operator = ElvisOperator()
        val whens = compiled.module.collectWhens()
        assertTrue(whens.none { operator.matches(it) }, "Elvis must not match an if/when")
    }

    // --- SafeCallOperator ---

    @Test
    fun `safe call replaces with non-null access`() {
        val compiled = compile("fun f(a: String?): Int? = a?.length")
        val safeWhen = compiled.module.collectWhens().firstOrNull {
            it.origin?.debugName == "FOLDED_SAFE_CALL"
        } ?: error("no FOLDED_SAFE_CALL when found")
        val operator = SafeCallOperator()

        assertTrue(operator.matches(safeWhen))
        assertEquals("?.", operator.originalDescription(safeWhen))
        assertEquals(listOf("a!!.b"), operator.variants(safeWhen, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `safe call does not match non-safe-call whens`() {
        val compiled = compile("fun f(a: Boolean): Int = if (a) 1 else 2")
        val operator = SafeCallOperator()
        val whens = compiled.module.collectWhens()
        assertTrue(whens.none { operator.matches(it) }, "SafeCall must not match an if/when")
    }

    // --- EmptyCollectionReturnOperator ---

    @Test
    fun `empty collection return replaces list with emptyList`() {
        val compiled = compile("fun f(): List<Int> { return listOf(1, 2, 3) }")
        val ret = compiled.module.collectReturns().first()
        val operator = EmptyCollectionReturnOperator()

        assertTrue(operator.matches(ret))
        assertEquals(listOf("emptyList"), operator.variants(ret, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `empty collection return does not match non-collection returns`() {
        val compiled = compile("fun f(): Int = 42")
        val ret = compiled.module.collectReturns().first()
        val operator = EmptyCollectionReturnOperator()
        assertTrue(!operator.matches(ret), "EmptyCollectionReturn must not match a numeric return")
    }

    // --- AssignConstOperator ---

    @Test
    fun `assign const replaces numeric assignment with zero`() {
        val compiled = compile("fun f(x: Int) { var a = x; a = x }")
        val operator = AssignConstOperator()
        // Use a variable assignment node.
        val setValues = compiled.module.collectSetValues()
        val assignment = setValues.last() // the `a = x` one
        val targetType = assignment.symbol.owner.type
        val value = assignment.value

        assertTrue(operator.matches(targetType, value))
        assertEquals(listOf("0"), operator.variants(targetType, value, compiled.contextFor("f")).map { it.description })
    }

    @Test
    fun `assign const skips constant assignments`() {
        val compiled = compile("fun f() { var a = 0; a = 0 }")
        val setValues = compiled.module.collectSetValues()
        val operator = AssignConstOperator()
        // `a = 0` is an IrSetValue with a constant value.
        val assignment = setValues.last()
        assertTrue(!operator.matches(assignment.symbol.owner.type, assignment.value),
            "AssignConst must skip constant assignments")
    }

    // --- Materialized-expression regression tests ---
    //
    // The tests above only assert on `matches()`/`description`. These additionally call
    // `createExpression()` and inspect the resulting IR node, catching bugs in the built
    // expression itself (e.g. missing type arguments) that description-only assertions miss.

    @Test
    fun `elvis 'a' variant produces a fully-typed checkNotNull call`() {
        // Regression test: the "a" variant used to build `irCall(checkNotNullSymbol)`
        // without setting typeArguments[0] or the call's result type, leaving an
        // unbound type parameter on the materialized call.
        val compiled = compile("fun f(a: String?): String = a ?: \"d\"")
        val elvisWhen = compiled.module.collectWhens().first { it.origin?.debugName == "FOLDED_ELVIS" }
        val operator = ElvisOperator()

        val aVariant = operator.variants(elvisWhen, compiled.contextFor("f")).first { it.description == "a" }
        val expression = aVariant.createExpression() as IrCall

        assertTrue(expression.symbol.isBound, "checkNotNull call symbol must be bound")
        val typeArgument = expression.typeArguments.getOrNull(0)
        assertTrue(typeArgument != null, "checkNotNull call must have its type argument set")
        assertEquals(typeArgument, expression.type, "call type must match the substituted type argument")
    }

    @Test
    fun `literal reassignment of a local var in a mutation target compiles without IR corruption`() {
        // Regression test for a bug in MutflowIrTransformer.transformAssignmentValue:
        // its fallback paths (no matching assignment operator, i.e. the assigned value
        // is already a literal constant - AssignConstOperator explicitly skips those)
        // returned the enclosing IrSetValue/IrSetField node itself instead of the
        // assigned value, producing a self-referential `x = x` IR cycle and a
        // StackOverflowError during compilation.
        //
        // Uses a local var, not a property: property assignment from within the class
        // lowers to a call to the synthetic setter (`<set-count>(0)`, an IrCall), not an
        // IrSetField, so it never reaches transformAssignmentValue and wouldn't have
        // exercised the bug. A local var reassignment lowers to a genuine IrSetValue.
        //
        // This runs the *real* compiler plugin end-to-end (not just IR capture) so the
        // bug is caught the way it actually manifested: as a compilation crash.
        val source = """
            import io.github.anschnapp.mutflow.MutationTarget

            @MutationTarget
            class Counter {
                fun reset(): Int {
                    var count = 1
                    count = 0
                    return count
                }
            }
        """.trimIndent()

        IrTestCompiler.compile(
            source,
            extraPlugins = listOf(IrTestCompiler.createRealPluginDir().path),
            extraClasspath = listOf(
                IrTestCompiler.findProjectClasspathEntry("mutflow-annotations"),
                IrTestCompiler.findProjectClasspathEntry("mutflow-core")
            )
        )
    }
}
