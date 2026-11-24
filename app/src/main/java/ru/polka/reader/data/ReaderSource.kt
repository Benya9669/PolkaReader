package ru.polka.reader.data

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.io.IOException

internal sealed class ReaderSource(val key: String) {
    open val archive: RangeEpub? = null
    abstract fun openPdf(): ParcelFileDescriptor
    open fun epubStream(): java.io.InputStream = throw IOException("Полный EPUB отсутствует")

    class Local(private val file: File) : ReaderSource(file.absolutePath) {
        override fun openPdf(): ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun epubStream() = file.inputStream()
    }

    class Remote private constructor(key: String, private val storage: StorageManager, private val bytes: RangeBook,
                                     private val lifetime: Job, override val archive: RangeEpub?) : ReaderSource(key), Closeable {
        private val worker = if (archive == null) HandlerThread("Polka PDF ranges").apply { start() } else null
        val receivedBytes: Long get() = bytes.loadedBytes

        override fun openPdf(): ParcelFileDescriptor {
            val thread = worker ?: throw IOException("Это издание в формате EPUB")
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                override fun onGetSize() = bytes.size
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                    bytes.read(offset, data, size)
                } catch (e: IOException) {
                    throw ErrnoException("read", OsConstants.EIO, e)
                } catch (e: CancellationException) {
                    throw ErrnoException("read", OsConstants.EBADF, e)
                }
                override fun onRelease() = Unit
            }, Handler(thread.looper))
        }

        override fun close() {
            lifetime.cancel()
            worker?.quitSafely()
        }

        companion object {
            fun open(context: Context, scope: String, file: BookFile, parent: Job?, load: suspend (Long, Long) -> ByteArray): Remote {
                // A server declining Range must leave the owning reader alive for the full-file fallback.
                val job = SupervisorJob(parent)
                try {
                    val bytes = RangeBook(file.size, { start, end -> runBlocking(job) { load(start, end) } })
                    bytes.bytes(0, 1) // Negotiate Range and a strong validator before handing bytes to a decoder.
                    val epub = if (file.format == "epub") RangeEpub(bytes) else null
                    val source = Remote("$scope/${file.id}-${file.sha256}", context.getSystemService(StorageManager::class.java),
                        bytes, job, epub)
                    try {
                        if (epub == null) source.openPdf().close() // Some Android builds may have no AppFuse service.
                        return source
                    } catch (e: IOException) { source.close(); throw RangeUnavailable(e) }
                    catch (e: Exception) { source.close(); throw e }
                } catch (e: Exception) { job.cancel(); throw e }
            }
        }
    }
}

internal class RangeUnavailable(cause: Exception? = null) : IOException("Сервер или устройство требует полной загрузки", cause)
