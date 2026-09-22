package com.neuron.ai.data.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the recursive-descent arithmetic evaluator backing the calculator tool. */
class ArithmeticEvaluatorTest {

    @Test
    fun `evaluates basic operations`() {
        assertEquals(5.0, SafeTools.ArithmeticEvaluator.evaluate("2 + 3"), 1e-9)
        assertEquals(6.0, SafeTools.ArithmeticEvaluator.evaluate("2 * 3"), 1e-9)
        assertEquals(2.5, SafeTools.ArithmeticEvaluator.evaluate("5 / 2"), 1e-9)
        assertEquals(1.0, SafeTools.ArithmeticEvaluator.evaluate("5 % 2"), 1e-9)
        assertEquals(-4.0, SafeTools.ArithmeticEvaluator.evaluate("2 - 6"), 1e-9)
    }

    @Test
    fun `respects operator precedence and parentheses`() {
        assertEquals(14.0, SafeTools.ArithmeticEvaluator.evaluate("2 + 3 * 4"), 1e-9)
        assertEquals(20.0, SafeTools.ArithmeticEvaluator.evaluate("(2 + 3) * 4"), 1e-9)
        assertEquals(8.0, SafeTools.ArithmeticEvaluator.evaluate("2 ** 3"), 1e-9)
        assertEquals(1.0, SafeTools.ArithmeticEvaluator.evaluate("2 ** 3 % 7"), 1e-9)
    }

    @Test
    fun `supports unary minus and floats`() {
        assertEquals(-3.5, SafeTools.ArithmeticEvaluator.evaluate("-3.5"), 1e-9)
        assertEquals(-9.0, SafeTools.ArithmeticEvaluator.evaluate("3 * -(3)"), 1e-9)
        assertEquals(0.5, SafeTools.ArithmeticEvaluator.evaluate("1e-1 * 5"), 1e-9)
    }

    @Test
    fun `rejects malformed and unsafe input`() {
        assertFails("2 +")
        assertFails("(1 + 2")
        assertFails("")
        // Letters are stripped by the sanitizer, so this becomes "3" -> parse ok.
        assertEquals(3.0, SafeTools.ArithmeticEvaluator.evaluate("abc3"), 1e-9)
        // A bare operator is not evaluable.
        assertFails("*")
    }

    private fun assertFails(expression: String) {
        val error = runCatching { SafeTools.ArithmeticEvaluator.evaluate(expression) }.exceptionOrNull()
        assertNotNull("Expected '$expression' to fail", error)
    }
}
