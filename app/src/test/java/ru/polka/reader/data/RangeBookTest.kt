package ru.polka.reader.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class RangeBookTest {
    @Test fun adjacentReadsShareBlocksAndTheCacheEvicts() {
        val all = ByteArray(20) { it.toByte() }
        val requests = mutableListOf<LongRange>()
        val book = RangeBook(all.size.toLong(), { start, end ->
            requests.add(start..end)
            all.copyOfRange(start.toInt(), end.toInt() + 1)
        }, blockSize = 8, maxBlocks = 2)
        assertArrayEquals(all.copyOfRange(2, 11), book.bytes(2, 9))
        assertArrayEquals(all.copyOfRange(8, 16), book.bytes(8, 8))
        assertEquals(1, book.read(19, ByteArray(10), 10))
        book.bytes(0, 1)
        assertEquals(listOf(0L..7L, 8L..15L, 16L..19L, 0L..7L), requests)
        assertThrows(IOException::class.java) { book.bytes(19, 2) }
        assertThrows(IOException::class.java) { RangeBook(8, { _, _ -> ByteArray(2) }).bytes(0, 1) }
    }

    @Test fun epubLoadsOnlyReferencedEntriesAndChecksTheirCrc() {
        val buffer = ByteArrayOutputStream()
        val stored = "stored resource".toByteArray()
        ZipOutputStream(buffer).use { zip ->
            fun add(name: String, bytes: ByteArray, storedEntry: Boolean = false) {
                val entry = ZipEntry(name)
                if (storedEntry) {
                    entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
            }
            add("META-INF/container.xml", "<root/>".toByteArray())
            add("chapter.xhtml", stored, true)
            add("unused.bin", ByteArray(2 * 1024 * 1024), true)
        }
        val all = buffer.toByteArray()
        fun source() = RangeBook(all.size.toLong(), { a, b -> all.copyOfRange(a.toInt(), b.toInt() + 1) })
        val blocks = source()
        val zip = RangeEpub(blocks)
        assertArrayEquals("<root/>".toByteArray(), zip.entry("META-INF/container.xml"))
        assertArrayEquals(stored, zip.entry("chapter.xhtml"))
        assertNull(zip.entry("../chapter.xhtml"))
        assertNull(zip.entry("chapter.xhtml/missing"))
        assertTrue(blocks.loadedBytes < all.size / 4)
        val offset = all.sliceArray(0..255).toString(Charsets.ISO_8859_1).indexOf("stored resource")
        assertTrue(offset >= 0)
        all[offset] = 12
        assertThrows(IOException::class.java) { RangeEpub(source()).entry("chapter.xhtml") }
    }
}
