package ru.polka.reader.sync

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class ReadingPoint(
    val locator: String,
    val percentage: Double,
    val chapter: String? = null,
    val device: String? = null,
    val updated: String? = null
)

data class Alternate(val fileId: Long, val format: String, val percentage: Double, val updated: String?)
data class OpenProgress(val point: ReadingPoint?, val alternate: Alternate? = null, val offline: Boolean = false)

/** The current position and the outbox share a row and therefore a transaction. */
@Entity(tableName = "reading_progress", primaryKeys = ["scope", "fileId"])
data class ProgressRow(
    val scope: String,
    val fileId: Long,
    @Embedded val point: ReadingPoint,
    val serverVersion: Long,
    val revision: Long,
    val syncedRevision: Long,
    val lastReadAt: Long,
    val conflictVersion: Long? = null,
    @Embedded(prefix = "conflict_") val conflict: ReadingPoint? = null,
    val error: String? = null
) {
    @get:Ignore val dirty: Boolean get() = revision != syncedRevision

    fun acknowledge(sentRevision: Long, version: Long, accepted: ReadingPoint): ProgressRow {
        require(sentRevision in syncedRevision..revision)
        return copy(
            point = if (revision == sentRevision) accepted else point,
            serverVersion = version, syncedRevision = sentRevision, error = null
        )
    }
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress WHERE scope = :scope AND fileId = :fileId")
    suspend fun get(scope: String, fileId: Long): ProgressRow?

    @Query("SELECT * FROM reading_progress WHERE scope = :scope AND fileId = :fileId")
    fun observe(scope: String, fileId: Long): Flow<ProgressRow?>

    @Query("SELECT * FROM reading_progress WHERE scope = :scope ORDER BY lastReadAt DESC, fileId")
    fun recent(scope: String): Flow<List<ProgressRow>>

    @Query("SELECT fileId FROM reading_progress WHERE scope = :scope AND revision != syncedRevision AND conflictVersion IS NULL")
    suspend fun pending(scope: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ProgressRow)

    @Query("DELETE FROM reading_progress WHERE scope = :scope AND fileId = :fileId")
    suspend fun remove(scope: String, fileId: Long)
}
