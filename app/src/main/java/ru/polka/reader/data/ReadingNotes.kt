package ru.polka.reader.data

import android.content.Context
import android.net.ConnectivityManager
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import java.io.IOException
import java.util.UUID

class ReadingNotes internal constructor(context: Context, private val data: PolkaData) {
    private val dao = data.db.notesDao()
    private val work = WorkManager.getInstance(context)
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        android.util.Log.e("PolkaNotes", "Ошибка очереди заметок", error)
    })
    private val locks = List(24) { Mutex() }
    private fun lock(scope: String, file: Long) = locks[((scope.hashCode() xor file.hashCode()) and Int.MAX_VALUE) % locks.size]
    private fun canSync(scope: String) = data.active.value?.let { it.scope == scope && it.authenticated } == true

    fun observe(scope: String, file: Long) = dao.observe(scope, file)

    suspend fun add(scope: String, file: Long, locator: String, quote: String, note: String) {
        require(data.active.value?.scope == scope && file > 0)
        val text = note.trim()
        val excerpt = quote.trim()
        require(locator.startsWith("epubcfi(") && locator.length <= 4000 && excerpt.length <= 4000 && text.length <= 10000 &&
            (excerpt.isNotEmpty() || text.isNotEmpty())) { "Проверьте текст выделения и заметки" }
        data.db.withTransaction {
            if (dao.list(scope, file).none { !it.removed && it.locator == locator && it.quote == excerpt && it.note == text }) {
                dao.save(LocalNote(scope, file, UUID.randomUUID().toString(), null, locator, excerpt, text, text,
                    pending = true, removed = false, revision = 1, touched = System.currentTimeMillis()))
            }
        }
        schedule(scope, file)
    }

    suspend fun change(item: LocalNote, note: String = item.note, remove: Boolean = false) {
        require(data.active.value?.scope == item.scope && note.length <= 10000)
        require(remove || note.isNotBlank() || item.quote.isNotEmpty())
        data.db.withTransaction {
            val latest = dao.get(item.scope, item.fileId, item.key) ?: return@withTransaction
            dao.save(latest.copy(note = note.trim(), removed = remove, pending = true, error = null,
                revision = latest.revision + 1, touched = System.currentTimeMillis()))
        }
        schedule(item.scope, item.fileId)
    }

    private fun schedule(scope: String, file: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val job = OneTimeWorkRequestBuilder<NotesWorker>()
            .setInputData(workDataOf("scope" to scope, "file" to file))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        work.enqueueUniqueWork("notes-$scope-$file", policy, job)
        // WorkManager may wait for validated internet on a Wi-Fi-only home LAN.
        if (canSync(scope) && network?.activeNetwork != null) background.launch { send(scope, file) }
    }

    internal suspend fun resume(scope: String) {
        if (canSync(scope)) dao.pendingFiles(scope).forEach { schedule(scope, it, ExistingWorkPolicy.KEEP) }
    }

    suspend fun refresh(scope: String, file: Long) = lock(scope, file).withLock {
        if (!canSync(scope)) return@withLock
        val server = data.annotations(scope, file)
        data.db.withTransaction {
            val stored = dao.list(scope, file)
            val ids = server.mapTo(mutableSetOf()) { it.id }
            for (row in stored) {
                if (!row.pending && row.serverId !in ids) dao.delete(scope, file, row.key)
            }
            for (entry in server) {
                val row = stored.find { it.serverId == entry.id }
                if (row?.pending == true || stored.any { it.serverId == null && it.locator == entry.locator &&
                            it.quote == entry.quote && it.createNote == entry.note }) continue
                dao.save(LocalNote(scope, file, row?.key ?: "server-${entry.id}", entry.id, entry.locator, entry.quote,
                    entry.note, entry.note, false, false, row?.revision ?: 0, row?.touched ?: 0))
            }
        }
    }

    /** False requests a network retry. An expired session resumes on the next login. */
    internal suspend fun send(scope: String, file: Long): Boolean = lock(scope, file).withLock {
        while (canSync(scope)) {
            val sent = dao.list(scope, file).firstOrNull { it.pending && it.error == null } ?: break
            try {
                val accepted = when {
                    sent.serverId == null -> data.addAnnotation(scope, file, sent.locator, sent.quote, sent.createNote)
                    sent.removed -> {
                        try { data.deleteAnnotation(scope, file, sent.serverId) } catch (e: ApiError) { if (e.status != 404) throw e }
                        null
                    }
                    else -> data.updateAnnotation(scope, file, sent.serverId, sent.note)
                }
                data.db.withTransaction {
                    val latest = dao.get(scope, file, sent.key) ?: return@withTransaction
                    if (accepted == null) dao.delete(scope, file, sent.key)
                    else {
                        val done = !latest.removed && latest.note == accepted.note
                        dao.save(latest.copy(serverId = accepted.id, pending = !done, error = null))
                    }
                }
            } catch (e: ApiError) {
                if (e.status == 401) break
                if (e.status == 408 || e.status == 429 || e.status >= 500) return@withLock false
                data.db.withTransaction {
                    val latest = dao.get(scope, file, sent.key) ?: return@withTransaction
                    if (latest.revision == sent.revision) {
                        if (e.status == 404 && sent.serverId != null && !sent.removed) {
                            // An edited note was deleted elsewhere. Preserve the new local text.
                            dao.save(latest.copy(serverId = null, createNote = latest.note))
                        } else dao.save(latest.copy(error = "Не удалось отправить (HTTP ${e.status})"))
                    }
                }
            } catch (_: IOException) { return@withLock false }
            catch (_: JSONException) { return@withLock false }
        }
        true
    }
}

class NotesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString("scope") ?: return Result.failure()
        val file = inputData.getLong("file", 0)
        if (file <= 0) return Result.failure()
        return if (PolkaData.get(applicationContext).notes.send(scope, file)) Result.success() else Result.retry()
    }
}
