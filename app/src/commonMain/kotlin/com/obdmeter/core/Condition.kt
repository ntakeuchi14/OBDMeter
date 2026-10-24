package com.obdmeter.core

import kotlin.math.abs

/**
 * 2 値ランプの点灯条件。計測値を `x` として書く。
 *
 * 例: `x >= 100`、`x > 90 && x < 110`、`x < 11.5 || x > 14.8`、`abs(x) > 5`
 * - 比較: `>` `>=` `<` `<=` `==` (`=` も可) `!=`
 * - 論理: `&&` (`and`)、`||` (`or`)、`!` (`not`)
 * - 計算: `+ - * /`、括弧、`abs()` `min()` `max()`
 * 真偽は 0 以外を真とする。
 */
class Condition private constructor(private val root: Node) {

    fun test(x: Double): Boolean = root.eval(x) != 0.0

    companion object {
        /** 条件式を解釈する。書き方が誤っていれば [ExpressionException] */
        fun compile(source: String): Condition {
            if (source.isBlank()) throw ExpressionException("条件式が空です")
            val p = Parser(source)
            val node = p.parseOr()
            p.expectEnd()
            return Condition(node)
        }

        /** 書き方が誤っていれば理由、正しければ null */
        fun check(source: String): String? = runCatching { compile(source) }.exceptionOrNull()?.message
    }

    private fun interface Node { fun eval(x: Double): Double }

    private class Parser(private val s: String) {
        private var i = 0
        private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun fail(msg: String): Nothing = throw ExpressionException("$msg (${i + 1} 文字目)")
        private fun b(v: Boolean) = if (v) 1.0 else 0.0

        /** 記号またはキーワード (前後が英字でないもの) を読めたら進める */
        private fun accept(token: String): Boolean {
            skip()
            if (!s.startsWith(token, i, ignoreCase = true)) return false
            if (token[0].isLetter()) {
                val end = i + token.length
                if (end < s.length && (s[end].isLetterOrDigit() || s[end] == '_')) return false
            }
            i += token.length
            return true
        }

        fun expectEnd() {
            skip()
            if (i < s.length) fail("「${s[i]}」が余分です")
        }

        fun parseOr(): Node {
            var a = parseAnd()
            while (accept("||") || accept("or")) {
                val l = a; val r = parseAnd()
                a = Node { x -> b(l.eval(x) != 0.0 || r.eval(x) != 0.0) }
            }
            return a
        }

        private fun parseAnd(): Node {
            var a = parseNot()
            while (accept("&&") || accept("and")) {
                val l = a; val r = parseNot()
                a = Node { x -> b(l.eval(x) != 0.0 && r.eval(x) != 0.0) }
            }
            return a
        }

        private fun parseNot(): Node {
            skip()
            // "!=" は比較なので、"!" の直後が "=" のときは否定として読まない
            if (s.startsWith("!", i) && !s.startsWith("!=", i)) {
                i++
                val a = parseNot()
                return Node { x -> b(a.eval(x) == 0.0) }
            }
            if (accept("not")) {
                val a = parseNot()
                return Node { x -> b(a.eval(x) == 0.0) }
            }
            return parseCompare()
        }

        private fun parseCompare(): Node {
            val l = parseSum()
            val op = listOf(">=", "<=", "==", "!=", ">", "<", "=").firstOrNull { accept(it) } ?: return l
            val r = parseSum()
            return when (op) {
                ">=" -> Node { x -> b(l.eval(x) >= r.eval(x)) }
                "<=" -> Node { x -> b(l.eval(x) <= r.eval(x)) }
                ">" -> Node { x -> b(l.eval(x) > r.eval(x)) }
                "<" -> Node { x -> b(l.eval(x) < r.eval(x)) }
                "!=" -> Node { x -> b(l.eval(x) != r.eval(x)) }
                else -> Node { x -> b(l.eval(x) == r.eval(x)) }
            }
        }

        private fun parseSum(): Node {
            var a = parseTerm()
            while (true) {
                skip()
                val c = s.getOrNull(i)
                if (c != '+' && c != '-') return a
                i++
                val l = a; val r = parseTerm()
                a = if (c == '+') Node { x -> l.eval(x) + r.eval(x) } else Node { x -> l.eval(x) - r.eval(x) }
            }
        }

        private fun parseTerm(): Node {
            var a = parseUnary()
            while (true) {
                skip()
                val c = s.getOrNull(i)
                if (c != '*' && c != '/') return a
                i++
                val l = a; val r = parseUnary()
                a = if (c == '*') Node { x -> l.eval(x) * r.eval(x) } else Node { x -> l.eval(x) / r.eval(x) }
            }
        }

        private fun parseUnary(): Node {
            skip()
            if (s.getOrNull(i) == '-') { i++; val a = parseUnary(); return Node { x -> -a.eval(x) } }
            if (s.getOrNull(i) == '+') { i++; return parseUnary() }
            return parsePrimary()
        }

        private fun parsePrimary(): Node {
            skip()
            val c = s.getOrNull(i) ?: fail("式が途中で終わっています")
            if (c == '(') {
                i++
                val a = parseOr()
                if (!accept(")")) fail("「)」がありません")
                return a
            }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                val v = s.substring(start, i).toDoubleOrNull() ?: run { i = start; fail("数値が読めません") }
                return Node { v }
            }
            if (c.isLetter()) {
                val start = i
                while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                val name = s.substring(start, i).lowercase()
                if (name == "x") return Node { x -> x }
                skip()
                if (s.getOrNull(i) != '(') { i = start; fail("「$name」は使えません (値は x と書きます)") }
                i++
                val args = mutableListOf(parseSum())
                while (accept(",")) args += parseSum()
                if (!accept(")")) fail("「)」がありません")
                return when (name) {
                    "abs" -> { if (args.size != 1) fail("abs の引数は 1 つです"); val a = args[0]; Node { x -> abs(a.eval(x)) } }
                    "min" -> { if (args.size < 2) fail("min の引数は 2 つ以上です"); Node { x -> args.minOf { it.eval(x) } } }
                    "max" -> { if (args.size < 2) fail("max の引数は 2 つ以上です"); Node { x -> args.maxOf { it.eval(x) } } }
                    else -> { i = start; fail("関数「$name」は使えません") }
                }
            }
            fail("「$c」は使えません")
        }
    }
}
