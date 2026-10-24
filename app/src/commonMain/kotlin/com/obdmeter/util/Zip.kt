package com.obdmeter.util

/**
 * zip の読み書き (Android と Web で共通)。
 * 書くときは無圧縮 (STORED)。読むときは無圧縮と deflate に対応する。
 */
object Zip {
    class Entry(val name: String, val data: ByteArray)

    private val crcTable = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
        c
    }

    fun crc32(data: ByteArray): Int {
        var c = -1
        for (b in data) c = crcTable[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }

    fun write(entries: List<Entry>): ByteArray {
        val out = Bytes()
        val central = Bytes()
        for (e in entries) {
            val name = e.name.encodeToByteArray()
            val crc = crc32(e.data)
            val offset = out.size
            out.u32(0x04034b50); out.u16(20); out.u16(0x0800); out.u16(0); out.u16(0); out.u16(0x21)
            out.u32(crc); out.u32(e.data.size); out.u32(e.data.size); out.u16(name.size); out.u16(0)
            out.bytes(name); out.bytes(e.data)
            central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0x0800); central.u16(0)
            central.u16(0); central.u16(0x21); central.u32(crc); central.u32(e.data.size); central.u32(e.data.size)
            central.u16(name.size); central.u16(0); central.u16(0); central.u16(0); central.u16(0); central.u32(0)
            central.u32(offset); central.bytes(name)
        }
        val cdOffset = out.size
        val cd = central.toByteArray()
        out.bytes(cd)
        out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
        out.u32(cd.size); out.u32(cdOffset); out.u16(0)
        return out.toByteArray()
    }

    /**
     * zip を読み、[accept] が受け付けた名前のエントリーを返す。[accept] は上限のバイト数 (受け付けないなら null)
     */
    fun read(zip: ByteArray, accept: (String) -> Int?): List<Entry> {
        // 終端レコードを探す
        var eocd = -1
        var i = zip.size - 22
        val stop = maxOf(0, zip.size - 22 - 65535)
        while (i >= stop) {
            if (le32(zip, i) == 0x06054b50) { eocd = i; break }
            i--
        }
        if (eocd < 0) throw IllegalArgumentException("zip ファイルではありません")
        val count = le16(zip, eocd + 10)
        var p = le32(zip, eocd + 16)
        val result = ArrayList<Entry>()
        repeat(count) {
            if (p < 0 || p + 46 > zip.size || le32(zip, p) != 0x02014b50) throw IllegalArgumentException("zip ファイルが壊れています")
            val method = le16(zip, p + 10)
            val csize = le32(zip, p + 20)
            val usize = le32(zip, p + 24)
            val nlen = le16(zip, p + 28)
            val elen = le16(zip, p + 30)
            val clen = le16(zip, p + 32)
            val local = le32(zip, p + 42)
            val name = zip.decodeToString(p + 46, p + 46 + nlen)
            p += 46 + nlen + elen + clen
            if (name.endsWith("/")) return@repeat
            val limit = accept(name) ?: return@repeat
            if (usize < 0 || usize > limit || csize < 0) throw IllegalArgumentException("ファイル内のデータが大きすぎます")
            if (local < 0 || local + 30 > zip.size) throw IllegalArgumentException("zip ファイルが壊れています")
            val start = local + 30 + le16(zip, local + 26) + le16(zip, local + 28)
            if (start + csize > zip.size) throw IllegalArgumentException("zip ファイルが壊れています")
            val data = when (method) {
                0 -> zip.copyOfRange(start, start + csize)
                8 -> Inflater(zip, start, start + csize, limit).run()
                else -> throw IllegalArgumentException("対応していない圧縮方式です ($method)")
            }
            result += Entry(name, data)
        }
        return result
    }

    private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, i: Int) = le16(b, i) or (le16(b, i + 2) shl 16)

    private class Bytes {
        private var buf = ByteArray(1024)
        var size = 0; private set
        private fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
        fun u16(v: Int) { ensure(2); buf[size++] = v.toByte(); buf[size++] = (v ushr 8).toByte() }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buf, size); size += b.size }
        fun toByteArray() = buf.copyOf(size)
    }

    /** deflate (RFC 1951) の展開 */
    private class Inflater(private val src: ByteArray, private var pos: Int, private val end: Int, private val limit: Int) {
        private var out = ByteArray(64 * 1024)
        private var n = 0
        private var bitBuf = 0
        private var bitCnt = 0

        private fun bit(): Int {
            if (bitCnt == 0) {
                if (pos >= end) throw IllegalArgumentException("zip ファイルが壊れています")
                bitBuf = src[pos++].toInt() and 0xFF
                bitCnt = 8
            }
            val b = bitBuf and 1
            bitBuf = bitBuf ushr 1
            bitCnt--
            return b
        }

        private fun bits(count: Int): Int {
            var v = 0
            for (i in 0 until count) v = v or (bit() shl i)
            return v
        }

        private fun put(b: Byte) {
            if (n >= out.size) {
                if (out.size >= limit) throw IllegalArgumentException("ファイル内のデータが大きすぎます")
                out = out.copyOf(minOf(out.size * 2, limit + 1).coerceAtLeast(n + 1))
            }
            out[n++] = b
            if (n > limit) throw IllegalArgumentException("ファイル内のデータが大きすぎます")
        }

        /** ハフマン符号表 (符号長ごとの数と、並べた記号) */
        private class Huffman(lengths: IntArray) {
            val counts = IntArray(16)
            val symbols = IntArray(lengths.size)
            init {
                for (l in lengths) counts[l]++
                counts[0] = 0
                val offs = IntArray(16)
                for (i in 1 until 16) offs[i] = offs[i - 1] + counts[i - 1]
                for (s in lengths.indices) if (lengths[s] != 0) symbols[offs[lengths[s]]++] = s
            }
        }

        private fun decode(h: Huffman): Int {
            var code = 0; var first = 0; var index = 0
            for (len in 1 until 16) {
                code = code or bit()
                val count = h.counts[len]
                if (code - count < first) return h.symbols[index + (code - first)]
                index += count
                first += count
                first = first shl 1
                code = code shl 1
            }
            throw IllegalArgumentException("zip ファイルが壊れています")
        }

        fun run(): ByteArray {
            do {
                val last = bit()
                when (bits(2)) {
                    0 -> {
                        bitCnt = 0
                        if (pos + 4 > end) throw IllegalArgumentException("zip ファイルが壊れています")
                        val len = (src[pos].toInt() and 0xFF) or ((src[pos + 1].toInt() and 0xFF) shl 8)
                        pos += 4
                        if (pos + len > end) throw IllegalArgumentException("zip ファイルが壊れています")
                        for (k in 0 until len) put(src[pos + k])
                        pos += len
                    }
                    1 -> codes(FIXED_LIT, FIXED_DIST)
                    2 -> dynamic()
                    else -> throw IllegalArgumentException("zip ファイルが壊れています")
                }
            } while (last == 0)
            return out.copyOf(n)
        }

        private fun dynamic() {
            val hlit = bits(5) + 257
            val hdist = bits(5) + 1
            val hclen = bits(4) + 4
            val cl = IntArray(19)
            for (i in 0 until hclen) cl[CL_ORDER[i]] = bits(3)
            val clh = Huffman(cl)
            val lens = IntArray(hlit + hdist)
            var i = 0
            while (i < hlit + hdist) {
                val sym = decode(clh)
                when {
                    sym < 16 -> lens[i++] = sym
                    sym == 16 -> {
                        if (i == 0) throw IllegalArgumentException("zip ファイルが壊れています")
                        val prev = lens[i - 1]
                        repeat(3 + bits(2)) { lens[i++] = prev }
                    }
                    sym == 17 -> repeat(3 + bits(3)) { lens[i++] = 0 }
                    else -> repeat(11 + bits(7)) { lens[i++] = 0 }
                }
            }
            codes(Huffman(lens.copyOfRange(0, hlit)), Huffman(lens.copyOfRange(hlit, hlit + hdist)))
        }

        private fun codes(lit: Huffman, dist: Huffman) {
            while (true) {
                val sym = decode(lit)
                if (sym < 256) put(sym.toByte())
                else if (sym == 256) return
                else {
                    val li = sym - 257
                    if (li >= 29) throw IllegalArgumentException("zip ファイルが壊れています")
                    val len = LEN_BASE[li] + bits(LEN_EXTRA[li])
                    val di = decode(dist)
                    if (di >= 30) throw IllegalArgumentException("zip ファイルが壊れています")
                    val d = DIST_BASE[di] + bits(DIST_EXTRA[di])
                    if (d > n) throw IllegalArgumentException("zip ファイルが壊れています")
                    for (k in 0 until len) put(out[n - d])
                }
            }
        }

        companion object {
            val CL_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
            val LEN_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
            val LEN_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
            val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
            val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
            val FIXED_LIT = Huffman(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } })
            val FIXED_DIST = Huffman(IntArray(30) { 5 })
        }
    }
}
