package gg.earu.chatsounds.parser.expr

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Compiled `[...]` dynamic expression: evaluated per param-tick, returns one value or a
 * list (`{0.2, 0.7}` or top-level commas). Null result = evaluation error, callers fall
 * back to the modifier's default (GMod pcall parity).
 */
class ExprFn internal constructor(private val roots: List<ExprNode>) {
    private val startTime = System.nanoTime()

    fun eval(): List<Double>? = try {
        val t = (System.nanoTime() - startTime) / 1e9
        roots.flatMap { it.eval(t) }
    } catch (_: Exception) {
        null
    }
}

internal sealed interface ExprNode {
    fun eval(t: Double): List<Double>

    class Num(val value: Double) : ExprNode {
        override fun eval(t: Double) = listOf(value)
    }

    class ListOf(val items: List<ExprNode>) : ExprNode {
        override fun eval(t: Double) = items.flatMap { it.eval(t) }
    }

    class Var(val name: String) : ExprNode {
        override fun eval(t: Double): List<Double> = when (name) {
            "pi", "PI" -> listOf(Math.PI)
            "t", "time", "clock" -> listOf(t)
            "input" -> listOf(0.0) // GMod passes no args to ExpressionFn, so input is effectively nil
            else -> error("unknown identifier $name")
        }
    }

    class Neg(val inner: ExprNode) : ExprNode {
        override fun eval(t: Double) = listOf(-inner.eval(t).first())
    }

    class Bin(val op: Char, val left: ExprNode, val right: ExprNode) : ExprNode {
        override fun eval(t: Double): List<Double> {
            val a = left.eval(t).first()
            val b = right.eval(t).first()
            return listOf(
                when (op) {
                    '+' -> a + b
                    '-' -> a - b
                    '*' -> a * b
                    '/' -> a / b
                    '%' -> a.mod(b)
                    '^' -> a.pow(b)
                    else -> error("bad op $op")
                }
            )
        }
    }

    class Call(val name: String, val args: List<ExprNode>) : ExprNode {
        override fun eval(t: Double): List<Double> {
            val a = args.map { it.eval(t).first() }
            fun a0() = a[0]
            fun a1() = a[1]
            return listOf(
                when (name) {
                    "t", "time", "clock" -> t
                    "sin" -> sin(a0()); "cos" -> cos(a0()); "tan" -> tan(a0())
                    "asin" -> asin(a0()); "acos" -> acos(a0()); "atan" -> atan(a0())
                    "atan2" -> atan2(a0(), a1())
                    "sinh" -> sinh(a0()); "cosh" -> cosh(a0()); "tanh" -> tanh(a0())
                    "exp" -> exp(a0()); "log" -> ln(a0()); "log10" -> log10(a0())
                    "sqrt" -> sqrt(a0()); "floor" -> floor(a0()); "ceil" -> ceil(a0())
                    "abs" -> abs(a0())
                    "sgn" -> if (a0() < 0) -1.0 else if (a0() > 0) 1.0 else 0.0
                    "sinc" -> if (a0() == 0.0) 1.0 else sin(a0()) / a0()
                    "deg" -> Math.toDegrees(a0()); "rad" -> Math.toRadians(a0())
                    "min" -> a.min(); "max" -> a.max()
                    "clamp" -> a0().coerceIn(a1(), a[2])
                    "pow" -> a0().pow(a1())
                    // Lua math.random semantics: () -> [0,1), (m) -> int 1..m, (m,n) -> int m..n
                    "rand", "random", "randomf" -> when (a.size) {
                        0 -> Random.nextDouble()
                        1 -> Random.nextInt(1, a0().toInt() + 1).toDouble()
                        else -> Random.nextInt(a0().toInt(), a1().toInt() + 1).toDouble()
                    }
                    else -> error("unknown function $name")
                }
            )
        }
    }
}

/**
 * Compiles the Lua-ish math expressions GMod accepted inside `[...]`: arithmetic, the math
 * env functions, `t()`, and `{a, b}` table constructors for list-valued modifiers. A closed
 * grammar, so no sandboxing/blacklist is needed. Returns null on any syntax error (GMod
 * CompileString-failure parity).
 */
object Expressions {
    fun compile(src: String): ExprFn? = try {
        val parser = Parser(src)
        val roots = parser.parseTopLevel()
        if (roots.isEmpty()) null else ExprFn(roots)
    } catch (_: Exception) {
        null
    }

    private class Parser(private val src: String) {
        private var pos = 0

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipWs()
            return src.getOrNull(pos)
        }

        private fun expect(c: Char) {
            check(peek() == c) { "expected $c at $pos" }
            pos++
        }

        fun parseTopLevel(): List<ExprNode> {
            val roots = ArrayList<ExprNode>()
            roots.add(parseExpr())
            while (peek() == ',') {
                pos++
                roots.add(parseExpr())
            }
            check(peek() == null) { "trailing input at $pos" }
            return roots
        }

        private fun parseExpr(): ExprNode = parseAdditive()

        private fun parseAdditive(): ExprNode {
            var left = parseMultiplicative()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') {
                    pos++
                    left = ExprNode.Bin(c, left, parseMultiplicative())
                } else return left
            }
        }

        private fun parseMultiplicative(): ExprNode {
            var left = parseUnary()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/' || c == '%') {
                    pos++
                    left = ExprNode.Bin(c, left, parseUnary())
                } else return left
            }
        }

        private fun parseUnary(): ExprNode =
            if (peek() == '-') {
                pos++
                ExprNode.Neg(parseUnary())
            } else parsePower()

        private fun parsePower(): ExprNode {
            val base = parsePrimary()
            return if (peek() == '^') {
                pos++
                ExprNode.Bin('^', base, parseUnary()) // right-assoc, unary binds tighter on the right
            } else base
        }

        private fun parsePrimary(): ExprNode {
            val c = peek() ?: error("unexpected end")
            return when {
                c == '(' -> {
                    pos++
                    val inner = parseExpr()
                    expect(')')
                    inner
                }
                c == '{' -> {
                    pos++
                    val items = ArrayList<ExprNode>()
                    if (peek() != '}') {
                        items.add(parseExpr())
                        while (peek() == ',') {
                            pos++
                            items.add(parseExpr())
                        }
                    }
                    expect('}')
                    ExprNode.ListOf(items)
                }
                c.isDigit() || c == '.' -> parseNumber()
                c.isLetter() || c == '_' -> parseIdentOrCall()
                else -> error("unexpected char $c at $pos")
            }
        }

        private fun parseNumber(): ExprNode {
            skipWs()
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            return ExprNode.Num(src.substring(start, pos).toDouble())
        }

        private fun parseIdentOrCall(): ExprNode {
            skipWs()
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val name = src.substring(start, pos)
            if (peek() == '(') {
                pos++
                val args = ArrayList<ExprNode>()
                if (peek() != ')') {
                    args.add(parseExpr())
                    while (peek() == ',') {
                        pos++
                        args.add(parseExpr())
                    }
                }
                expect(')')
                return ExprNode.Call(name, args)
            }
            return ExprNode.Var(name)
        }
    }
}
