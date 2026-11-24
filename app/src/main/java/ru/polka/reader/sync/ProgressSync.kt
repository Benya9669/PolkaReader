package ru.polka.reader.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import ru.polka.reader.data.ApiError
import ru.polka.reader.data.PolkaData
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProgressSync private constructor(context: Context) {
    private val data = PolkaData.get(context)
    private val db = data.db
    private val dao = db.progressDao()
    private val ledger = PositionLedger(db)
    private val work = WorkManager.getInstance(context)
    private val network = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        Log.e("PolkaProgress", "Ошибка сохранения позиции", e)
    })
    private val writes = Mutex()
    private val networkLocks = List(32) { Mutex() }
    private val timers = mutableMapOf<Key, Job>()
    private val device = ("Android · " + Build.MODEL).take(80)

    private data class Key(val scope: String, val fileId: Long)

    init {
        network.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                data.active.value?.takeIf { it.authenticated }?.let { resume(it.scope) }
            }
        })
    }

    fun observe(scope: String, fileId: Long) = dao.observe(scope, fileId)
    fun observeRecent(scope: String) = dao.recent(scope)

    private fun lock(scope: String, fileId: Long): Mutex =
        networkLocks[(Key(scope, fileId).hashCode() and Int.MAX_VALUE) % networkLocks.size]

    private fun authenticated(scope: String): Boolean =
        data.active.value?.let { it.scope == scope && it.authenticated } == true

    suspend fun open(scope: String, fileId: Long): OpenProgress = fetch(scope, fileId, markOpened = true)

    /** Cache the exact server locator before a downloaded book goes offline. */
    suspend fun preload(scope: String, fileId: Long) {
        withTimeoutOrNull(5000L) { fetch(scope, fileId, markOpened = false) }
    }

    private suspend fun fetch(scope: String, fileId: Long, markOpened: Boolean): OpenProgress = lock(scope, fileId).withLock {
        val cached = writes.withLock { dao.get(scope, fileId) }
        val offline = !authenticated(scope) || network.activeNetwork == null
        if (cached?.dirty == true || offline) {
            if (!offline && cached?.conflictVersion == null) flush(scope, fileId)
            return@withLock OpenProgress(cached?.point, offline = offline)
        }
        val remote = try {
            data.request(scope, "GET", "/reading/progress/$fileId").serverProgress()
        } catch (e: IOException) {
            return@withLock OpenProgress(cached?.point, offline = true)
        } catch (e: JSONException) {
            Log.w("PolkaProgress", "Некорректный ответ при чтении позиции", e)
            return@withLock OpenProgress(cached?.point, offline = true)
        }
        ledger.restore(scope, fileId, remote, markOpened)
    }

    /** Enter the write mutex in renderer event order, independently of the screen lifecycle. */
    fun record(scope: String, fileId: Long, point: ReadingPoint, force: Boolean = false): Job =
        jobs.launch(start = CoroutineStart.UNDISPATCHED) {
            require(isReadingLocator(point.locator) && point.percentage in 0.0..1.0)
            val changed = writes.withLock {
                ledger.edit(scope, fileId, point, device, force)
            }
            if (changed) enqueue(scope, fileId)
            schedule(scope, fileId, QUIET_MS)
        }

    /** Foreground attempt also works on a home Wi-Fi network without validated internet. */
    fun flush(scope: String, fileId: Long) {
        jobs.launch {
            writes.withLock { /* wait for the renderer's last event */ }
            schedule(scope, fileId, 0)
        }
    }

    fun resume(scope: String) {
        jobs.launch {
            if (!authenticated(scope)) return@launch
            data.notes.resume(scope)
            for (fileId in dao.pending(scope)) {
                enqueue(scope, fileId, ExistingWorkPolicy.KEEP)
                schedule(scope, fileId, 0)
            }
        }
    }

    suspend fun resolve(scope: String, fileId: Long, useLocal: Boolean): ReadingPoint? =
        lock(scope, fileId).withLock {
            val result = writes.withLock {
                ledger.resolve(scope, fileId, useLocal)
            }
            if (useLocal) {
                enqueue(scope, fileId)
                schedule(scope, fileId, 0)
            }
            result
        }

    private fun enqueue(scope: String, fileId: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val request = OneTimeWorkRequestBuilder<ProgressWorker>()
            .setInputData(workDataOf("scope" to scope, "fileId" to fileId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(QUIET_MS, TimeUnit.MILLISECONDS)
            .build()
        // A clean -> dirty transition can coincide with the previous worker completing.
        work.enqueueUniqueWork("progress-$scope-$fileId", policy, request)
    }

    private fun schedule(scope: String, fileId: Long, waitMs: Long) {
        val key = Key(scope, fileId)
        synchronized(timers) {
            timers.remove(key)?.cancel()
            val timer = jobs.launch(start = CoroutineStart.LAZY) {
                delay(waitMs)
                synchronized(timers) {
                    if (timers[key] !== coroutineContext[Job]) return@launch
                    // Only cancel timers. Never cancel a PUT after the server might have accepted it.
                    timers.remove(key)
                }
                if (network.activeNetwork != null) upload(scope, fileId)
            }
            timers[key] = timer
            timer.start()
        }
    }

    internal suspend fun pendingDelay(scope: String, fileId: Long): Long {
        val row = dao.get(scope, fileId) ?: return 0
        if (!row.dirty || row.conflictVersion != null) return 0
        val age = System.currentTimeMillis() - row.lastReadAt
        return if (age in 0 until QUIET_MS) QUIET_MS - age else 0
    }

    internal enum class Upload { FINISHED, MORE, RETRY }

    internal suspend fun upload(scope: String, fileId: Long): Upload = lock(scope, fileId).withLock {
        if (!authenticated(scope)) return@withLock Upload.FINISHED
        val sent = dao.get(scope, fileId) ?: return@withLock Upload.FINISHED
        if (!sent.dirty || sent.conflictVersion != null) return@withLock Upload.FINISHED
        try {
            val result = data.request(scope, "PUT", "/reading/progress/$fileId", sent.payload()).serverProgress()
            val accepted = result.point ?: throw IOException("Сервер не подтвердил сохранение позиции")
            if (ledger.accepted(sent, result.version, accepted)) Upload.MORE else Upload.FINISHED
        } catch (e: ApiError) {
            when (e.status) {
                409 -> {
                    val current = try { e.json.optJSONObject("current")?.serverProgress() }
                        catch (_: IOException) { null } catch (_: JSONException) { null }
                    if (current == null) {
                        ledger.error(sent, "Сервер не передал позицию для разрешения конфликта")
                        return@withLock Upload.RETRY
                    }
                    ledger.conflict(sent, current)
                    Upload.FINISHED
                }
                401 -> Upload.FINISHED // resume() requeues after reauthentication.
                else -> {
                    ledger.error(sent, "Ошибка синхронизации (HTTP ${e.status})")
                    if (e.status == 429 || e.status == 408 || e.status >= 500) Upload.RETRY else Upload.FINISHED
                }
            }
        } catch (e: IOException) {
            ledger.error(sent, "Сервер недоступен. Позиция сохранена на устройстве")
            Upload.RETRY
        } catch (e: JSONException) {
            ledger.error(sent, "Не удалось разобрать ответ сервера")
            Upload.RETRY
        }
    }

    companion object {
        private const val QUIET_MS = 1500L
        @Volatile private var instance: ProgressSync? = null
        fun get(context: Context): ProgressSync = instance ?: synchronized(this) {
            instance ?: ProgressSync(context.applicationContext).also { instance = it }
        }
    }
}
