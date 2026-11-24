package ru.polka.reader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Temporary, account-scoped publications for opening a book without adding an offline copy. */
internal class ReadingCache(
    private val root: File,
    private val byteLimit: Long = 512L * 1024 * 1024,
    private val fileLimit: Int = 4,
) {
    private val gate = Mutex()
    private val readers = mutableMapOf<File, Int>()

    suspend fun <T> withFile(
        scope: String, file: BookFile, fetch: suspend (File) -> Unit, read: suspend (File) -> T,
    ): T {
        require(scope.matches(Regex("[a-f0-9]{64}")) && file.readable && file.id > 0)
        require(file.sha256.matches(Regex("[A-Fa-f0-9]{64}"))) { "Некорректная контрольная сумма файла" }
        if (file.size !in 1..byteLimit) throw IOException("Файл не помещается во временный кеш. Выберите «Скачать».")
        val dir = File(root, scope)
        val target = File(dir, "${file.id}-${file.sha256}.${file.format}")
        var pinned = false
        try {
            withContext(Dispatchers.IO) {
                gate.withLock {
                    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Не удалось создать кеш чтения")
                    val stored = root.walkTopDown().maxDepth(2).filter { it.isFile }.toList()
                    // All writers use this gate; any .part still on disk was left by a stopped process.
                    stored.filter { it.extension == "part" }.forEach(::discard)
                    if (!target.isFile || target.length() != file.size) {
                        if (target.exists()) discard(target)
                        val books = stored.filter { it.extension != "part" && it != target }
                        var count = books.size
                        var bytes = books.sumOf(File::length)
                        val oldest = books.filterNot { it in readers }.sortedBy(File::lastModified).iterator()
                        while (count >= fileLimit || bytes > byteLimit - file.size || dir.usableSpace < file.size) {
                            if (!oldest.hasNext()) throw IOException("Недостаточно места во временном кеше чтения")
                            val victim = oldest.next()
                            bytes -= victim.length()
                            discard(victim)
                            count--
                        }
                        val partial = File.createTempFile("reading-", ".part", dir)
                        try {
                            fetch(partial)
                            if (!partial.renameTo(target)) throw IOException("Не удалось подготовить файл для чтения")
                        } finally {
                            if (partial.exists()) discard(partial)
                        }
                    }
                    target.setLastModified(System.currentTimeMillis())
                    readers[target] = (readers[target] ?: 0) + 1
                    pinned = true
                }
            }
            return read(target)
        } finally {
            if (pinned) withContext(NonCancellable) {
                gate.withLock {
                    val remaining = readers.getValue(target) - 1
                    if (remaining == 0) readers.remove(target) else readers[target] = remaining
                }
            }
        }
    }

    private fun discard(file: File) {
        if (!file.delete() && file.exists()) throw IOException("Не удалось очистить временный кеш чтения")
    }
}
