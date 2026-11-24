package ru.polka.reader.data

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Readers sharing a publication also share a bounded LRU, never credentials or bytes with another book. */
internal class RangeBook(
    val size: Long, private val fetch: (Long, Long) -> ByteArray,
    private val blockSize: Int = 64 * 1024, private val maxBlocks: Int = 64,
) {
    @Volatile var loadedBytes = 0L
        private set
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = this.size > maxBlocks
    }

    @Synchronized
    fun read(offset: Long, into: ByteArray, count: Int): Int {
        if (offset !in 0..size || count !in 0..into.size) throw IOException("Некорректный диапазон книги")
        val length = minOf(count.toLong(), size - offset).toInt()
        var copied = 0
        while (copied < length) {
            val position = offset + copied
            val index = position / blockSize
            val start = index * blockSize
            val bytes = blocks.getOrPut(index) {
                val expected = minOf(blockSize.toLong(), size - start)
                fetch(start, start + expected - 1).also {
                    if (it.size.toLong() != expected) throw IOException("Сервер вернул неполную часть книги")
                    loadedBytes += expected
                }
            }
            val within = (position - start).toInt()
            val take = minOf(length - copied, bytes.size - within)
            bytes.copyInto(into, copied, within, within + take)
            copied += take
        }
        return copied
    }

    fun bytes(offset: Long, count: Int): ByteArray {
        if (count < 0 || offset !in 0..size || count > size - offset) throw IOException("Раздел выходит за границы файла")
        return ByteArray(count).also { read(offset, it, count) }
    }
}

/** ZIP32 central directory and single stored/deflated EPUB resources; other ZIP layouts use the full-file reader. */
internal class RangeEpub(private val book: RangeBook) {
    internal class Unsupported : IOException("Особенности этого EPUB требуют загрузки полного файла")
    private data class Entry(val header: Long, val method: Int, val compressed: Int, val size: Int, val crc: Long)
    private val entries = mutableMapOf<String, Entry>()

    init {
        val tailSize = minOf(book.size, 65535L + 22).toInt()
        val tail = book.bytes(book.size - tailSize, tailSize)
        val end = (tail.size - 22 downTo 0).firstOrNull { i ->
            tail.u32(i) == 0x06054b50L && i + 22 + tail.u16(i + 20) == tail.size
        } ?: throw Unsupported()
        val count = tail.u16(end + 10)
        val length = tail.u32(end + 12)
        val offset = tail.u32(end + 16)
        if (tail.u32(end + 4) != 0L || count != tail.u16(end + 8) || count >= 65535 ||
            length > 8L * 1024 * 1024 || offset + length > book.size - tailSize + end) throw Unsupported()
        val index = book.bytes(offset, length.toInt())
        var pos = 0
        repeat(count) {
            if (pos + 46 > index.size || index.u32(pos) != 0x02014b50L) throw Unsupported()
            val nameLength = index.u16(pos + 28)
            val next = pos + 46 + nameLength + index.u16(pos + 30) + index.u16(pos + 32)
            if (next > index.size || index.u16(pos + 34) != 0) throw Unsupported()
            val name = index.copyOfRange(pos + 46, pos + 46 + nameLength).toString(Charsets.UTF_8)
            val method = index.u16(pos + 10)
            val compressed = index.u32(pos + 20)
            val size = index.u32(pos + 24)
            val header = index.u32(pos + 42)
            if (index.u16(pos + 8) and 1 != 0 || method !in listOf(0, 8) || header >= offset ||
                size > Int.MAX_VALUE || compressed > Int.MAX_VALUE) throw Unsupported()
            if (!name.endsWith("/") && entries.put(name, Entry(header, method, compressed.toInt(), size.toInt(),
                    index.u32(pos + 16))) != null) throw Unsupported()
            pos = next
        }
        if ("META-INF/container.xml" !in entries) throw Unsupported()
    }

    fun entry(name: String): ByteArray? {
        if (name.length > 2048 || name.contains('\\') || name.split('/').any { it in listOf("", ".", "..") }) return null
        val entry = entries[name] ?: return null
        // Never allocate according to unbounded archive-supplied lengths or return unchecked decompressed bytes.
        if (entry.size > 24 * 1024 * 1024 || entry.compressed > 24 * 1024 * 1024) throw IOException("Ресурс EPUB слишком велик")
        val header = book.bytes(entry.header, 30)
        if (header.u32(0) != 0x04034b50L || header.u16(8) != entry.method) throw IOException("Повреждён заголовок EPUB")
        val data = book.bytes(entry.header + 30 + header.u16(26) + header.u16(28), entry.compressed)
        val result = if (entry.method == 0) data else {
            val inflater = Inflater(true)
            try {
                InflaterInputStream(ByteArrayInputStream(data), inflater).use { input ->
                    ByteArray(entry.size).also { out ->
                        var pos = 0
                        while (pos < out.size) {
                            val read = input.read(out, pos, out.size - pos)
                            if (read < 0) throw IOException("Неполный раздел EPUB")
                            pos += read
                        }
                        if (input.read() != -1 || !inflater.finished()) throw IOException("Некорректный размер раздела EPUB")
                    }
                }
            } finally { inflater.end() }
        }
        if (result.size != entry.size || CRC32().apply { update(result) }.value != entry.crc)
            throw IOException("Раздел EPUB не прошёл проверку CRC")
        return result
    }

    private fun ByteArray.u16(i: Int) = (this[i].toInt() and 255) or ((this[i + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(i: Int) = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
}
