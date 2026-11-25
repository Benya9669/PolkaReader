package ru.polka.reader.sync

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.polka.reader.data.ReaderDatabase
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProgressStorageTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun ReaderDatabase.withDatabase(action: suspend (ReaderDatabase) -> Unit) {
        try { action(this) } finally { close() }
    }

    @Test fun repairPreservesExactPositionAndSkipsStaleOrConflictingRows() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().withDatabase { db ->
            val ledger = PositionLedger(db)
            val point = ReadingPoint("epubcfi(/6/2!/4/2/1:42)", 0.0, "Акт первый")
            ledger.restore("one", 1, ServerProgress(8, point))
            val before = db.progressDao().get("one", 1)!!
            assertFalse(ledger.repairPercentage("two", 1, point.locator, .12))
            assertFalse(ledger.repairPercentage("one", 1, "epubcfi(/6/4!/4/2/1:0)", .12))
            assertTrue(ledger.repairPercentage("one", 1, point.locator, .12))
            val after = db.progressDao().get("one", 1)!!
            assertEquals(point.copy(percentage = .12), after.point)
            assertEquals(before.lastReadAt, after.lastReadAt)
            assertEquals(8L, after.serverVersion)
            assertTrue(after.dirty)
            assertFalse(ledger.repairPercentage("one", 1, point.locator, .13))
            db.progressDao().put(before.copy(conflictVersion = 9))
            assertFalse(ledger.repairPercentage("one", 1, point.locator, .12))
        }
    }

    @Test fun preloadingDownloadsUsesReadingTimeInsteadOfDownloadOrder() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().withDatabase { db ->
            val ledger = PositionLedger(db)
            val scope = "test-account"
            for ((id, stamp) in listOf(1L to "2026-08-02T12:20:00Z", 2L to "2026-07-01T12:20:00")) {
                val point = ReadingPoint("17", 0.5, updated = stamp)
                ledger.restore(scope, id, ServerProgress(4, point), markOpened = false)
            }
            assertTrue(db.progressDao().get(scope, 1)!!.lastReadAt > db.progressDao().get(scope, 2)!!.lastReadAt)
            assertTrue(db.progressDao().pending(scope).isEmpty())
        }
    }

    @Test fun offlineOutboxSurvivesReopenAndScopesDoNotMix() = runBlocking {
        val name = "position-test-" + UUID.randomUUID() + ".db"
        val firstScope = "server-a/account-1"
        val local = ReadingPoint("17", 0.5)
        try {
            Room.databaseBuilder(context, ReaderDatabase::class.java, name).build().withDatabase { db ->
                val ledger = PositionLedger(db)
                assertTrue(ledger.edit(firstScope, 1, local, "Телефон", false))
                assertFalse(ledger.edit(firstScope, 1, ReadingPoint("18", 0.6), "Телефон", false))
            }
            Room.databaseBuilder(context, ReaderDatabase::class.java, name).build().withDatabase { db ->
                val dao = db.progressDao()
                assertEquals(listOf(1L), dao.pending(firstScope))
                assertEquals("18", dao.get(firstScope, 1)!!.point.locator)
                for (other in listOf("server-a/account-2", "server-b/account-1")) {
                    assertNull(dao.get(other, 1))
                    assertTrue(dao.pending(other).isEmpty())
                }
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun delayedGetAndPutCannotEraseNewerLocalEdit() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().withDatabase { db ->
            val ledger = PositionLedger(db)
            val dao = db.progressDao()
            val scope = "test-account"
            val browser = ReadingPoint("17", 0.5, device = "Браузер")
            assertEquals(browser, ledger.restore(scope, 1, ServerProgress(3, browser)).point)
            assertTrue(dao.pending(scope).isEmpty())
            ledger.edit(scope, 1, ReadingPoint("18", 0.53), "Телефон", false)
            val sent = dao.get(scope, 1)!!
            ledger.edit(scope, 1, ReadingPoint("7", 0.2), "Телефон", false)
            assertEquals("7", ledger.restore(scope, 1, ServerProgress(3, browser)).point!!.locator)
            assertTrue(ledger.accepted(sent, 4, sent.point))
            val rebased = dao.get(scope, 1)!!
            assertEquals("7", rebased.point.locator)
            assertEquals(4L, rebased.serverVersion)
            assertFalse(ledger.accepted(rebased, 5, rebased.point))
            assertTrue(dao.pending(scope).isEmpty())
        }
    }

    @Test fun bothConflictChoicesRespectAnIntentionalMoveBackwards() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().withDatabase { db ->
            val ledger = PositionLedger(db)
            val dao = db.progressDao()
            val scope = "test-account"
            val remote = ReadingPoint("6", 0.15, device = "Планшет", updated = "2026-11-24T00:00:00")
            for (useLocal in listOf(true, false)) {
                val id = if (useLocal) 1L else 2L
                ledger.edit(scope, id, ReadingPoint("3", 0.08), "Телефон", false)
                val sent = dao.get(scope, id)!!
                ledger.edit(scope, id, ReadingPoint("2", 0.05), "Телефон", false)
                ledger.conflict(sent, ServerProgress(8, remote))
                val held = dao.get(scope, id)!!
                assertEquals(remote, held.conflict)
                assertEquals("2", held.point.locator)
                assertFalse(dao.pending(scope).contains(id))
                val chosen = ledger.resolve(scope, id, useLocal)
                assertEquals(if (useLocal) "2" else "6", chosen!!.locator)
                val after = dao.get(scope, id)!!
                assertEquals(8L, after.serverVersion)
                assertEquals(useLocal, after.dirty)
                assertNull(after.conflictVersion)
            }
        }
    }
}
