package com.obdmeter.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipTest {
    private val r = java.util.Random(1)
    private val datas = listOf(
        ByteArray(0),
        "hello hello hello hello".repeat(1000).toByteArray(),
        ByteArray(300_000) { r.nextInt(256).toByte() },
        ByteArray(200_000) { (it % 37 + r.nextInt(3)).toByte() },
        "日本語のテキスト".repeat(500).encodeToByteArray(),
    )

    @Test
    fun readsDeflatedZipFromJava() {
        for (level in listOf(0, 1, 6, 9)) {
            val bo = java.io.ByteArrayOutputStream()
            ZipOutputStream(bo).use { z ->
                z.setLevel(level)
                datas.forEachIndexed { i, d -> z.putNextEntry(ZipEntry("f$i")); z.write(d); z.closeEntry() }
            }
            val got = Zip.read(bo.toByteArray()) { 10_000_000 }
            assertEquals(datas.size, got.size)
            got.forEachIndexed { i, e -> assertArrayEquals(datas[i], e.data) }
        }
    }

    @Test
    fun writtenZipIsReadableByJava() {
        val w = Zip.write(datas.mapIndexed { i, d -> Zip.Entry("d/ファイル$i", d) })
        var i = 0
        ZipInputStream(w.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                assertEquals("d/ファイル$i", e.name)
                assertArrayEquals(datas[i], z.readBytes())
                i++
            }
        }
        assertEquals(datas.size, i)
    }

    @Test
    fun crcMatchesJava() {
        val c = CRC32().apply { update(datas[2]) }
        assertEquals(c.value.toInt(), Zip.crc32(datas[2]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooLarge() {
        val w = Zip.write(listOf(Zip.Entry("a", ByteArray(1000))))
        Zip.read(w) { 10 }
    }
}
