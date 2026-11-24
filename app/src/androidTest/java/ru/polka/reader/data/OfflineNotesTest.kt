package ru.polka.reader.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Three fresh instrumentation processes, run by tools/qa/offline_notes.py on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class OfflineNotesTest {
    @Test fun persistedEdit() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("notesStage")
        assumeTrue("Запустите через offline_notes.py", stage in setOf("prepare", "offline", "restore"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = PolkaData.get(context)
        val state = File(context.cacheDir, "notes-qa-state.json")
        if (stage == "prepare") {
            val credentials = File(context.cacheDir, "notes-qa-login")
            val password = try { credentials.readText() } finally { check(credentials.delete()) }
            val account = data.login("http://127.0.0.1:18763", "readerone", password)
            val book = data.catalog(account.scope, "Северная полка", null, 1).items.first()
            val edition = book.editions.first { ed -> ed.files.any { it.format == "epub" } }
            val file = edition.files.first { it.format == "epub" }
            data.markOpened(data.download(account.scope, book, edition, file))
            state.writeText(JSONObject().put("scope", account.scope).put("file", file.id)
                .put("quote", "Проверка офлайн " + UUID.randomUUID()).toString())
        } else {
            val meta = JSONObject(state.readText())
            val scope = meta.getString("scope")
            val file = meta.getLong("file")
            val quote = meta.getString("quote")
            assertEquals(scope, data.active.value?.scope)
            if (stage == "offline") {
                data.notes.add(scope, file, "epubcfi(/6/2!/4/2/1:0)", quote, "Черновик")
                val draft = data.notes.observe(scope, file).first().single { it.quote == quote }
                data.notes.change(draft, "Заметка из авиарежима")
            }
            val saved = data.notes.observe(scope, file).first().single { it.quote == quote }
            assertEquals("Заметка из авиарежима", saved.note)
            assertTrue(saved.pending)
            assertNull(saved.serverId)
        }
    }
}
