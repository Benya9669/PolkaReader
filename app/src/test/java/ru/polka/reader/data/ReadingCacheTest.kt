package ru.polka.reader.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ReadingCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun book(id: Long) = BookFile(id, "test.epub", "epub", 2, null, "a".repeat(64))

    @Test fun completedCopiesAreReusedAfterReopenWithinTheSameAccountOnly() = runBlocking {
        val root = folder.newFolder()
        val cache = ReadingCache(root)
        val first = cache.withFile(scope, book(1), { it.writeText("ab") }) { it }
        val reopened = ReadingCache(root)
        assertEquals(first, reopened.withFile(scope, book(1), { error("Unexpected download") }) { it })
        val separate = reopened.withFile(other, book(1), { it.writeText("cd") }) { it }
        assertNotEquals(first, separate)
        assertEquals("ab", first.readText())
        assertEquals("cd", separate.readText())
    }

    @Test fun evictionRespectsGlobalBudgetAndOpenReaders() = runBlocking {
        val root = folder.newFolder()
        val cache = ReadingCache(root, byteLimit = 2, fileLimit = 1)
        val first = cache.withFile(scope, book(1), { it.writeText("ab") }) { active ->
            val error = runCatching { cache.withFile(other, book(2), { it.writeText("cd") }) { } }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(active.isFile)
            active
        }
        cache.withFile(other, book(2), { it.writeText("cd") }) { assertEquals("cd", it.readText()) }
        assertFalse(first.exists())
        assertEquals(2L, root.walkTopDown().filter { it.isFile }.sumOf { it.length() })
        val oversized = runCatching { cache.withFile(scope, book(3).copy(size = 3), { error("Fetched oversized file") }) { } }
        assertTrue(oversized.exceptionOrNull() is IOException)
    }

    @Test fun failedCancelledAndAbandonedTransfersLeaveNoReadableFile() = runBlocking {
        val root = folder.newFolder()
        val cache = ReadingCache(root)
        for (cause in listOf(IOException("test"), CancellationException("test"))) {
            val failure = runCatching {
                cache.withFile(scope, book(1), { partial ->
                    partial.writeText("a")
                    throw cause
                }) { error("Opened incomplete book") }
            }
            assertEquals(cause.javaClass, failure.exceptionOrNull()?.javaClass)
            assertTrue(root.walkTopDown().none { it.isFile })
        }
        val abandoned = root.resolve(scope).resolve("reading-abandoned.part").apply { writeText("a") }
        ReadingCache(root).withFile(scope, book(1), { it.writeText("ab") }) { assertTrue(it.isFile) }
        assertFalse(abandoned.exists())
    }
}
