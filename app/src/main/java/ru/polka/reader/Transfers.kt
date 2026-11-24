package ru.polka.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.polka.reader.data.*

internal data class Transfer(val bytes: Long = 0, val total: Long = 0, val error: String? = null)

internal class Transfers(application: Application) : AndroidViewModel(application) {
    private val sync = ru.polka.reader.sync.ProgressSync.get(application)
    private val jobs = mutableMapOf<Long, Job>()
    private val states = MutableStateFlow<Map<Long, Transfer>>(emptyMap())
    val progress = states.asStateFlow()
    fun start(data: PolkaData, scope: String, book: Book, edition: Edition, file: BookFile) {
        if (jobs[file.id]?.isActive == true) return
        states.update { it + (file.id to Transfer(total = file.size)) }
        jobs[file.id] = viewModelScope.launch {
            try {
                sync.preload(scope, file.id)
                data.download(scope, book, edition, file) { count, total ->
                    states.update { it + (file.id to Transfer(count, total)) }
                }
                states.update { it - file.id }
            } catch (e: CancellationException) {
                states.update { it - file.id }
                throw e
            } catch (e: Exception) {
                states.update { it + (file.id to Transfer(error = friendly(e))) }
            }
        }
    }
    fun cancel(id: Long) { jobs[id]?.cancel() }
}
