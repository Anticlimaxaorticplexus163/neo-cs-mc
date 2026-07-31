package gg.earu.chatsounds

import gg.earu.chatsounds.parser.expr.Expressions
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionsTest {
    private fun eval(src: String): List<Double>? = Expressions.compile(src)?.eval()

    @Test
    fun arithmetic() {
        assertEquals(listOf(7.0), eval("1 + 2 * 3"))
        assertEquals(listOf(9.0), eval("(1 + 2) * 3"))
        assertEquals(listOf(8.0), eval("2 ^ 3"))
        assertEquals(listOf(-4.0), eval("-4"))
        assertEquals(listOf(1.0), eval("7 % 3"))
    }

    @Test
    fun functions() {
        assertEquals(listOf(1.0), eval("cos(0)"))
        assertEquals(listOf(2.0), eval("clamp(5, 0, 2)"))
        assertEquals(listOf(3.0), eval("max(1, 3, 2)"))
        assertEquals(listOf(1.0), eval("sinc(0)"))
        assertTrue(abs(eval("pi")!![0] - Math.PI) < 1e-12)
    }

    @Test
    fun lists() {
        assertEquals(listOf(0.2, 0.7), eval("{0.2, 0.7}"))
        assertEquals(listOf(1.0, 4.0), eval("1, 2*2"))
    }

    @Test
    fun `time starts near zero`() {
        val t = eval("t")!![0]
        assertTrue(t >= 0.0 && t < 1.0)
    }

    @Test
    fun `garbage returns null`() {
        assertNull(Expressions.compile("os.exit()")?.eval()) // unknown identifier -> eval error -> null
        assertNull(Expressions.compile(""))
        assertNull(Expressions.compile("1 +"))
    }
}
