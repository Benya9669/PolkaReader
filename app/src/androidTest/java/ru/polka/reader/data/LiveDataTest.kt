package ru.polka.reader.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Opt in on a dedicated emulator with tools/qa/serve_fixture.py and the temporary fixture password. */
@RunWith(AndroidJUnit4::class)
class LiveDataTest {
    @Test fun separateUsersCanReadTheSameFilePrivately() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "polka-live-qa")
        assumeTrue("Для этого теста нужен изолированный сервер с синтетической библиотекой", input.isFile)
        val password = try { input.readText().trim() } finally { check(input.delete()) }
        val server = "http://127.0.0.1:18763" // adb reverse tcp:18763 tcp:18763
        val data = PolkaData.get(context)
        val denied = runCatching { data.login(server, "readerone", UUID.randomUUID().toString()) }.exceptionOrNull()
        assertEquals(401, (denied as ApiError).status)
        val one = data.login(server, "readerone", password)
        data.verify(one.scope)
        val library = data.libraries(one.scope).single { it.name == "Проверочная библиотека" }
        assertFalse(library.canConvert)
        val page = data.catalog(one.scope, "", library.id, 1)
        val pdfBook = page.items.first { b -> b.editions.any { e -> e.files.any { it.format == "pdf" } } }
        val byAuthor = data.catalog(one.scope, pdfBook.authors, library.id, 1)
        assertTrue(byAuthor.items.any { it.id == pdfBook.id })
        val edition = data.book(one.scope, pdfBook.id).editions.first { e -> e.files.any { it.format == "pdf" } }
        val file = edition.files.first { it.format == "pdf" }
        assertEquals(pdfBook.id, data.bookForFile(one.scope, file.id).id)
        if (edition.hasCover) assertTrue(data.cover(one.scope, edition.id)!!.length() > 0)
        data.remove(one.scope, file.id)
        val temporaryPath = data.withReadingSource(one.scope, file.id, pdfBook.id, { _, _ -> }) { copy, _, permanent ->
            assertFalse(permanent)
            assertEquals(file.id, copy.fileId)
            assertTrue(File(copy.path).toPath().startsWith(context.cacheDir.toPath()))
            assertEquals(file.size, File(copy.path).length())
            assertTrue(data.downloads(one.scope).first().none { it.fileId == file.id })
            data.markOpened(copy)
            assertEquals(copy.fileId, data.recentBook(one.scope).first()?.fileId)
            copy.path
        }
        data.withReadingSource(one.scope, file.id, pdfBook.id, { _, _ -> error("Повторное получение готовой книги") }) { copy, _, permanent ->
            assertFalse(permanent)
            assertEquals(temporaryPath, copy.path)
        }
        val damaged = file.copy(sha256 = "0".repeat(64))
        val wrongEdition = edition.copy(files = listOf(damaged))
        val mismatch = runCatching {
            data.download(one.scope, pdfBook.copy(editions = listOf(wrongEdition)), wrongEdition, damaged)
        }.exceptionOrNull()
        assertTrue(mismatch is IOException)
        assertTrue(data.downloads(one.scope).first().none { it.fileId == file.id })
        val local = data.download(one.scope, pdfBook, edition, file)
        data.markOpened(one.scope, file.id)
        assertEquals(file.id, data.lastOpened(one.scope).first()?.fileId)
        val bytes = File(local.path).readBytes()
        assertEquals(file.size, bytes.size.toLong())
        assertEquals(file.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).hex())
        val restored = SessionVault(context)
        assertEquals(one, restored.account())
        assertNotNull(restored.decrypt(one))
        val epubBook = page.items.first { b -> b.editions.any { e -> e.files.any { it.format == "epub" } } }
        val epub = epubBook.editions.flatMap { it.files }.first { it.format == "epub" }
        data.withReadingSource(one.scope, epub.id, epubBook.id, { _, _ -> }) { copy, _, _ -> data.markOpened(copy) }
        assertEquals(listOf(epubBook.id, pdfBook.id), data.recentBooks(one.scope).first().take(2).map { it.workId })
        val locator = "epubcfi(/6/2!/4/2/2:0)"
        val quote = "Офлайн " + UUID.randomUUID()
        // Simulate a lost POST response; replay uses Polka's deduplication.
        val accepted = data.addAnnotation(one.scope, epub.id, locator, quote, "Черновик")
        // Revoke remotely while the client still holds its session. Offline content must remain readable.
        data.request(one.scope, "POST", "/auth/logout")
        val revoked = runCatching { data.verify(one.scope) }.exceptionOrNull()
        assertEquals(401, (revoked as ApiError).status)
        assertEquals(false, data.active.value?.authenticated)
        assertEquals(local.path, data.downloads(one.scope).first().single { it.fileId == file.id }.path)
        data.notes.add(one.scope, epub.id, locator, quote, "Черновик")
        val pending = data.notes.observe(one.scope, epub.id).first().single { it.quote == quote }
        assertTrue(pending.pending)
        data.notes.change(pending, "Дополнено без сети")
        assertTrue(data.notes.send(one.scope, epub.id))
        assertTrue(data.notes.observe(one.scope, epub.id).first().single { it.key == pending.key }.pending)
        assertEquals(one.scope, data.login(server, "readerone", password).scope)
        assertTrue(data.notes.send(one.scope, epub.id))
        data.notes.refresh(one.scope, epub.id)
        val uploaded = data.notes.observe(one.scope, epub.id).first().single { it.quote == quote }
        assertFalse(uploaded.pending)
        assertEquals(accepted.id, uploaded.serverId)
        assertEquals("Дополнено без сети", data.annotations(one.scope, epub.id).single { it.quote == quote }.note)

        val marker = "Тест " + UUID.randomUUID().toString()
        val mark = data.addBookmark(one.scope, file.id, "17", marker)
        assertTrue(data.bookmarks(one.scope, file.id).any { it == mark })
        val note = data.addAnnotation(one.scope, epub.id, locator, "Абзац", marker)
        assertEquals("Изменено", data.updateAnnotation(one.scope, epub.id, note.id, "Изменено").note)
        try {
            data.logout(one.scope)
            assertNull(data.active.value)
            val two = data.login(server, "readertwo", password)
            assertNotEquals(one.scope, two.scope)
            assertTrue(data.downloads(two.scope).first().none { it.fileId == file.id })
            assertNull(data.lastOpened(two.scope).first())
            assertTrue(data.recentBooks(two.scope).first().isEmpty())
            assertTrue(data.notes.observe(two.scope, epub.id).first().none { it.quote == quote })
            assertTrue(data.bookmarks(two.scope, file.id).none { it.id == mark.id })
            assertTrue(data.annotations(two.scope, epub.id).none { it.id == note.id })
            val stale = runCatching { data.verify(one.scope) }.exceptionOrNull()
            assertEquals(401, (stale as ApiError).status)
        } finally {
            data.login(server, "readerone", password)
            data.notes.change(uploaded, remove = true)
            assertTrue(data.notes.send(one.scope, epub.id))
            assertTrue(data.annotations(one.scope, epub.id).none { it.id == accepted.id })
            data.deleteAnnotation(one.scope, epub.id, note.id)
            data.deleteBookmark(one.scope, file.id, mark.id)
            data.remove(one.scope, file.id)
            assertFalse(File(local.path).exists())
            assertEquals(pdfBook.id, data.book(one.scope, pdfBook.id).id)
            data.logout(one.scope)
        }
    }
}
