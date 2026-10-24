package com.obdmeter.core

/**
 * カスタム PID の換算式。OBD Fusion などと同じく、応答データの 1 バイト目を A、2 バイト目を B … として書く。
 *
 * 例:
 *   (A*256+B)/4          回転数
 *   A-40                 温度
 *   signed(A)*0.5        符号付き 8bit
 *   signed16(A*256+B)/10 符号付き 16bit
 *   bit(A,3)             A の bit3 (0 か 1)
 *
 * 使える演算子: + - * / % ( ) 、関数: abs min max signed signed16 bit round floor
 * 数値は 10 進のほか 0x1F のような 16 進も書ける。
 */
class Expression private constructor(
    val source: String,
    private val root: Node,
    /** 式が参照する最大バイト数 (A のみなら 1、A と B なら 2)。応答の最低長として使う。 */
    val requiredBytes: Int,
) {
    fun evaluate(data: IntArray): Double {
        if (data.size < requiredBytes) {
            throw ExpressionException("応答が ${data.size} バイトしかありません (式は ${requiredBytes} バイト必要)")
        }
        val v = root.eval(data)
        if (v.isNaN() || v.isInfinite()) throw ExpressionException("計算結果が数値になりません (0 で割った可能性)")
        return v
    }

    companion object {
        fun compile(source: String): Expression {
            if (source.isBlank()) throw ExpressionException("式が空です")
            val p = Parser(source)
            val node = p.parseExpression()
            p.expectEnd()
            return Expression(source, node, p.maxVar + 1)
        }
    }

    // ---------------------------------------------------------------------

    private sealed interface Node {
        fun eval(d: IntArray): Double
    }

    private class Num(val v: Double) : Node {
        override fun eval(d: IntArray) = v
    }

    private class Var(val index: Int) : Node {
        override fun eval(d: IntArray) = d[index].toDouble()
    }

    private class Unary(val op: Char, val a: Node) : Node {
        override fun eval(d: IntArray) = if (op == '-') -a.eval(d) else a.eval(d)
    }

    private class Binary(val op: Char, val a: Node, val b: Node) : Node {
        override fun eval(d: IntArray): Double {
            val x = a.eval(d)
            val y = b.eval(d)
            return when (op) {
                '+' -> x + y
                '-' -> x - y
                '*' -> x * y
                '/' -> x / y
                '%' -> x % y
                else -> error(op)
            }
        }
    }

    private class Call(val name: String, val args: List<Node>) : Node {
        override fun eval(d: IntArray): Double {
            val v = args.map { it.eval(d) }
            return when (name) {
                "abs" -> kotlin.math.abs(v[0])
                "min" -> v.min()
                "max" -> v.max()
                "round" -> Math.round(v[0]).toDouble()
                "floor" -> kotlin.math.floor(v[0])
                "signed" -> { val x = v[0].toInt() and 0xFF; (if (x >= 0x80) x - 0x100 else x).toDouble() }
                "signed16" -> { val x = v[0].toInt() and 0xFFFF; (if (x >= 0x8000) x - 0x10000 else x).toDouble() }
                "bit" -> ((v[0].toLong() shr v[1].toInt()) and 1L).toDouble()
                else -> error(name)
            }
        }
    }

    private class Parser(private val s: String) {
        private var i = 0
        var maxVar = -1

        private val arity = mapOf(
            "abs" to 1..1, "min" to 2..8, "max" to 2..8, "round" to 1..1, "floor" to 1..1,
            "signed" to 1..1, "signed16" to 1..1, "bit" to 2..2,
        )

        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun peek(): Char? { skip(); return s.getOrNull(i) }

        private fun fail(msg: String): Nothing = throw ExpressionException("$msg (${i + 1} 文字目)")

        fun expectEnd() { if (peek() != null) fail("「${s[i]}」が余分です") }

        fun parseExpression(): Node {
            var left = parseTerm()
            while (true) {
                val c = peek()
                if (c == '+' || c == '-') { i++; left = Binary(c, left, parseTerm()) } else return left
            }
        }

        private fun parseTerm(): Node {
            var left = parseUnary()
            while (true) {
                val c = peek()
                if (c == '*' || c == '/' || c == '%') { i++; left = Binary(c, left, parseUnary()) } else return left
            }
        }

        private fun parseUnary(): Node {
            val c = peek()
            if (c == '-' || c == '+') { i++; return Unary(c, parseUnary()) }
            return parsePrimary()
        }

        private fun parsePrimary(): Node {
            val c = peek() ?: fail("式が途中で終わっています")
            if (c == '(') {
                i++
                val n = parseExpression()
                if (peek() != ')') fail("「)」がありません")
                i++
                return n
            }
            if (c.isDigit() || c == '.') return parseNumber()
            if (c.isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val word = s.substring(start, i)
                if (peek() == '(') {
                    val name = word.lowercase()
                    val range = arity[name] ?: run { i = start; fail("関数「$word」は使えません") }
                    i++
                    val args = mutableListOf<Node>()
                    if (peek() != ')') {
                        args += parseExpression()
                        while (peek() == ',') { i++; args += parseExpression() }
                    }
                    if (peek() != ')') fail("「)」がありません")
                    i++
                    if (args.size !in range) fail("$name の引数の数が違います")
                    return Call(name, args)
                }
                if (word.length == 1 && word[0].uppercaseChar() in 'A'..'Z') {
                    val idx = word[0].uppercaseChar() - 'A'
                    if (idx > maxVar) maxVar = idx
                    return Var(idx)
                }
                i = start
                fail("「$word」は使えません (変数は A〜Z の 1 文字)")
            }
            fail("「$c」は使えません")
        }

        private fun parseNumber(): Node {
            val start = i
            if (s.startsWith("0x", i, ignoreCase = true)) {
                i += 2
                val hs = i
                while (i < s.length && (s[i].isDigit() || s[i].uppercaseChar() in 'A'..'F')) i++
                if (i == hs) fail("16 進数が不正です")
                return Num(s.substring(hs, i).toLong(16).toDouble())
            }
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            val v = s.substring(start, i).toDoubleOrNull() ?: run { i = start; fail("数値が不正です") }
            return Num(v)
        }
    }
}

class ExpressionException(message: String) : Exception(message)
