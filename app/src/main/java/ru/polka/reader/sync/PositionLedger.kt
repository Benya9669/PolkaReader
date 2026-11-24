package ru.polka.reader.sync

import androidx.room.withTransaction
import ru.polka.reader.data.ReaderDatabase
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

internal class PositionLedger(private val db: ReaderDatabase) {
    private val dao = db.progressDao()

    suspend fun restore(scope: String, fileId: Long, remote: ServerProgress, markOpened: Boolean = true): OpenProgress = db.withTransaction {
        val current = dao.get(scope, fileId)
        if (current?.dirty == true) return@withTransaction OpenProgress(current.point)
        val point = remote.point ?: current?.point
        if (point != null) {
            val revision = current?.revision ?: 0L
            val lastRead = if (markOpened) System.currentTimeMillis() else
                maxOf(current?.lastReadAt ?: 0L, serverTimestamp(remote.point?.updated))
            dao.put(ProgressRow(scope, fileId, point, remote.version, revision, revision, lastRead))
        }
        OpenProgress(point, remote.alternate)
    }

    private fun serverTimestamp(value: String?): Long {
        if (value == null) return 0
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            // SQLite-backed v0.3.0 reports its UTC timestamps without an offset.
            try { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }
            catch (_: DateTimeParseException) { 0L }
        }
    }

    /** Returns true when a durable worker must be scheduled for this edit. */
    suspend fun edit(scope: String, fileId: Long, point: ReadingPoint, device: String, force: Boolean): Boolean =
        db.withTransaction {
            val previous = dao.get(scope, fileId)
            if (!force && previous?.point?.locator == point.locator) return@withTransaction false
            val now = System.currentTimeMillis()
            val next = point.copy(chapter = point.chapter?.take(500), device = device, updated = Instant.ofEpochMilli(now).toString())
            dao.put(ProgressRow(scope, fileId, next, previous?.serverVersion ?: 0,
                (previous?.revision ?: 0) + 1, previous?.syncedRevision ?: 0, now,
                previous?.conflictVersion, previous?.conflict))
            previous?.dirty != true || previous.error != null
        }

    suspend fun accepted(sent: ProgressRow, version: Long, accepted: ReadingPoint): Boolean = db.withTransaction {
        val latest = dao.get(sent.scope, sent.fileId) ?: return@withTransaction false
        val row = latest.acknowledge(sent.revision, version, accepted)
        dao.put(row)
        row.dirty
    }

    suspend fun conflict(sent: ProgressRow, current: ServerProgress) = db.withTransaction {
        val latest = dao.get(sent.scope, sent.fileId) ?: return@withTransaction
        dao.put(latest.copy(conflictVersion = current.version, conflict = current.point, error = null))
    }

    suspend fun resolve(scope: String, fileId: Long, useLocal: Boolean): ReadingPoint? = db.withTransaction {
        val row = dao.get(scope, fileId) ?: return@withTransaction null
        val version = row.conflictVersion ?: return@withTransaction row.point
        val remote = row.conflict
        when {
            useLocal -> dao.put(row.copy(serverVersion = version, conflict = null, conflictVersion = null, error = null))
            remote == null -> dao.remove(scope, fileId)
            else -> dao.put(row.copy(point = remote, serverVersion = version, syncedRevision = row.revision,
                conflict = null, conflictVersion = null, error = null))
        }
        if (useLocal) row.point else remote
    }

    suspend fun error(sent: ProgressRow, message: String) = db.withTransaction {
        val row = dao.get(sent.scope, sent.fileId)
        if (row != null && row.revision == sent.revision) dao.put(row.copy(error = message))
    }
}
