package io.github.anschnapp.mutflow.compiler

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetPatternTest {

    private fun matches(pattern: String, fqName: String): Boolean {
        val compiled = MutflowIrTransformer.compileTargetPatterns(listOf(pattern))
        return compiled.any { it.matches(fqName) }
    }

    @Test
    fun `exact class name matches`() {
        assertTrue(matches("com.example.Calculator", "com.example.Calculator"))
    }

    @Test
    fun `exact class name does not match different class`() {
        assertFalse(matches("com.example.Calculator", "com.example.Validator"))
    }

    @Test
    fun `exact class name does not match subpackage`() {
        assertFalse(matches("com.example.Calculator", "com.example.sub.Calculator"))
    }

    @Test
    fun `single wildcard matches any class in package`() {
        assertTrue(matches("com.example.*", "com.example.Calculator"))
        assertTrue(matches("com.example.*", "com.example.Validator"))
    }

    @Test
    fun `single wildcard does not cross package boundaries`() {
        assertFalse(matches("com.example.*", "com.example.sub.Calculator"))
    }

    @Test
    fun `single wildcard does not match package itself`() {
        assertFalse(matches("com.example.*", "com.example"))
    }

    @Test
    fun `double wildcard matches any depth`() {
        assertTrue(matches("com.example.**", "com.example.Calculator"))
        assertTrue(matches("com.example.**", "com.example.sub.Calculator"))
        assertTrue(matches("com.example.**", "com.example.sub.deep.Calculator"))
    }

    @Test
    fun `double wildcard does not match parent package`() {
        assertFalse(matches("com.example.**", "com.other.Calculator"))
    }

    @Test
    fun `wildcard in class name position`() {
        assertTrue(matches("com.example.*Service", "com.example.PaymentService"))
        assertTrue(matches("com.example.*Service", "com.example.AuditService"))
        assertFalse(matches("com.example.*Service", "com.example.Calculator"))
    }

    @Test
    fun `prefix wildcard in class name`() {
        assertTrue(matches("com.example.Pricing*", "com.example.PricingService"))
        assertTrue(matches("com.example.Pricing*", "com.example.PricingEngine"))
        assertFalse(matches("com.example.Pricing*", "com.example.Calculator"))
    }

    @Test
    fun `empty pattern list matches nothing`() {
        val compiled = MutflowIrTransformer.compileTargetPatterns(emptyList())
        assertFalse(compiled.any { it.matches("com.example.Calculator") })
    }

    @Test
    fun `multiple patterns - any match is sufficient`() {
        val compiled = MutflowIrTransformer.compileTargetPatterns(listOf(
            "com.example.Calculator",
            "com.example.service.*"
        ))
        assertTrue(compiled.any { it.matches("com.example.Calculator") })
        assertTrue(compiled.any { it.matches("com.example.service.PaymentService") })
        assertFalse(compiled.any { it.matches("com.example.Validator") })
    }

    @Test
    fun `dots in package names are literal, not regex wildcards`() {
        assertFalse(matches("com.example.Calculator", "comXexampleXCalculator"))
    }
}
