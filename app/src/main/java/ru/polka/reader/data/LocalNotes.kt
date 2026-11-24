package ru.polka.reader.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reader_notes", primaryKeys = ["scope", "fileId", "key"])
data class LocalNote(
    val scope: String, val fileId: Long, val key: String, val serverId: Long?,
    val locator: String, val quote: String, val note: String,
    // Polka deduplicates POST by (locator, quote, note). Keep this payload across lost replies.
    val createNote: String, val pending: Boolean, val removed: Boolean,
    val revision: Long, val touched: Long, val error: String? = null,
)

@Dao
interface NotesDao {
    @Query("SELECT * FROM reader_notes WHERE scope=:scope AND fileId=:fileId ORDER BY touched DESC, serverId DESC")
    fun observe(scope: String, fileId: Long): Flow<List<LocalNote>>

    @Query("SELECT * FROM reader_notes WHERE scope=:scope AND fileId=:fileId")
    suspend fun list(scope: String, fileId: Long): List<LocalNote>

    @Query("SELECT * FROM reader_notes WHERE scope=:scope AND fileId=:fileId AND `key`=:key")
    suspend fun get(scope: String, fileId: Long, key: String): LocalNote?

    @Query("SELECT DISTINCT fileId FROM reader_notes WHERE scope=:scope AND pending=1 AND error IS NULL")
    suspend fun pendingFiles(scope: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(note: LocalNote)

    @Query("DELETE FROM reader_notes WHERE scope=:scope AND fileId=:fileId AND `key`=:key")
    suspend fun delete(scope: String, fileId: Long, key: String)
}
