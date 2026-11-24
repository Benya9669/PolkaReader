package ru.polka.reader.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class ProgressWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scope = inputData.getString("scope") ?: return Result.failure()
        val fileId = inputData.getLong("fileId", 0)
        if (fileId <= 0) return Result.failure()
        val sync = ProgressSync.get(applicationContext)
        while (true) {
            val remaining = sync.pendingDelay(scope, fileId)
            if (remaining > 0) {
                delay(remaining)
                continue
            }
            when (sync.upload(scope, fileId)) {
                ProgressSync.Upload.FINISHED -> return Result.success()
                ProgressSync.Upload.RETRY -> return Result.retry()
                ProgressSync.Upload.MORE -> continue
            }
        }
    }
}
