package ru.polka.reader.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import ru.polka.reader.sync.ProgressDao
import ru.polka.reader.sync.ProgressRow

@Entity(tableName = "catalog_cache", primaryKeys = ["scope", "path"])
data class CatalogEntry(val scope: String, val path: String, val json: String)

@Entity(tableName = "downloads", primaryKeys = ["scope", "fileId"])
data class Download(
    val scope: String,
    val fileId: Long,
    val workId: Long,
    val editionId: Long,
    val title: String,
    val authors: String,
    val name: String,
    val format: String,
    val path: String,
    val bytes: Long,
    val lastOpenedAt: Long? = null,
)

@Dao
interface LibraryDao {
    @Query("SELECT json FROM catalog_cache WHERE scope = :scope AND path = :path")
    suspend fun cached(scope: String, path: String): String?

    @Query("SELECT json FROM catalog_cache WHERE scope = :scope AND path = :path")
    fun observeEntry(scope: String, path: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(entry: CatalogEntry)

    @Query("SELECT * FROM downloads WHERE scope = :scope ORDER BY title, fileId")
    fun downloads(scope: String): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE scope = :scope AND lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC, fileId LIMIT 1")
    fun lastOpened(scope: String): Flow<Download?>

    @Query("UPDATE downloads SET lastOpenedAt = :whenMillis WHERE scope = :scope AND fileId = :fileId")
    suspend fun markOpened(scope: String, fileId: Long, whenMillis: Long)

    @Query("SELECT * FROM downloads WHERE scope = :scope AND fileId = :id")
    suspend fun download(scope: String, id: Long): Download?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: Download)

    @Query("DELETE FROM downloads WHERE scope = :scope AND fileId = :id")
    suspend fun remove(scope: String, id: Long)
}

internal val addReaderNotes = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS reader_notes (
            scope TEXT NOT NULL, fileId INTEGER NOT NULL, `key` TEXT NOT NULL, serverId INTEGER,
            locator TEXT NOT NULL, quote TEXT NOT NULL, note TEXT NOT NULL, createNote TEXT NOT NULL,
            pending INTEGER NOT NULL, removed INTEGER NOT NULL, revision INTEGER NOT NULL, touched INTEGER NOT NULL,
            error TEXT, PRIMARY KEY(scope, fileId, `key`))""")
    }
}

@Database(entities = [CatalogEntry::class, Download::class, ProgressRow::class, LocalNote::class], version = 2, exportSchema = false)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun progressDao(): ProgressDao
    abstract fun notesDao(): NotesDao
}
