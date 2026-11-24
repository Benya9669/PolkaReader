package ru.polka.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import ru.polka.reader.data.*
import ru.polka.reader.reader.*
import ru.polka.reader.sync.*
import java.io.File

private fun ReadingPoint.toReader() = ReaderPlace(locator, percentage, chapter)
private fun description(p: ReadingPoint?): String = p?.let {
    listOfNotNull(placeLabel(it), it.device, it.updated?.replace('T', ' ')?.substringBefore('.')).joinToString(" · ")
} ?: "Начало книги"

@Composable
internal fun OpenReader(account: Account, data: PolkaData, sync: ProgressSync, fileId: Long, workId: Long?,
                        appearance: Appearance, onExit: () -> Unit) {
    var source by remember(fileId) { mutableStateOf<Download?>(null) }
    var permanent by remember(fileId) { mutableStateOf(false) }
    var progress by remember(fileId) { mutableStateOf<Pair<Long, Long>?>(null) }
    var error by remember(fileId) { mutableStateOf<String?>(null) }
    var retry by remember(fileId) { mutableIntStateOf(0) }
    LaunchedEffect(account.scope, fileId, workId, retry) {
        error = null
        progress = null
        try {
            data.withReadingFile(account.scope, fileId, workId, { bytes, total -> progress = bytes to total }) { copy, offline ->
                permanent = offline
                source = copy
                awaitCancellation() // Keep this file available to the renderer for the screen's lifetime.
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = friendly(e) }
    }
    val copy = source
    if (copy != null) {
        ReaderRoute(account, data, sync, copy, permanent, appearance, onExit)
    } else {
        BackHandler { onExit() }
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) Notice(error!!) { retry++ } else {
                    Text("Подготавливаем книгу")
                    val transfer = progress
                    if (transfer == null) LinearProgressIndicator() else {
                        LinearProgressIndicator(progress = { (transfer.first.toDouble() / transfer.second.coerceAtLeast(1)).toFloat() })
                        Text("${sizeLabel(transfer.first)} из ${sizeLabel(transfer.second)}")
                    }
                }
                TextButton(onClick = onExit) { Text(if (error == null) "Отменить" else "Назад") }
            }
        }
    }
}

@Composable
private fun ReaderRoute(account: Account, data: PolkaData, sync: ProgressSync, copy: Download, permanent: Boolean,
                        appearance: Appearance, onExit: () -> Unit) {
    val scope = account.scope
    val fileId = copy.fileId
    val coroutine = rememberCoroutineScope()
    var start by remember { mutableStateOf<OpenProgress?>(null) }
    var alternate by remember { mutableStateOf<Alternate?>(null) }
    var jump by remember { mutableStateOf<ReaderJump?>(null) }
    var forceNext by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var initialError by remember { mutableStateOf<String?>(null) }
    val local by remember(scope, fileId) { sync.observe(scope, fileId) }.collectAsState(initial = null)
    val settings by appearance.book.collectAsState(initial = BookSettings())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, fileId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) sync.flush(scope, fileId) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sync.flush(scope, fileId) }
    }
    LaunchedEffect(fileId, retry) {
        initialError = null
        try {
            require(File(copy.path).isFile) { "Локальная копия отсутствует. Скачайте файл повторно." }
            val restored = sync.open(scope, fileId)
            alternate = restored.alternate
            start = restored
            data.markOpened(copy)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { initialError = friendly(e) }
    }
    BackHandler { onExit() }
    val restored = start
    if (restored == null) {
        if (initialError == null) Waiting() else Column(Modifier.safeDrawingPadding()) {
            Notice(initialError!!) { retry++ }; TextButton(onExit) { Text("Назад") }
        }
        return
    }
    val status = when {
        local?.conflictVersion != null -> "Конфликт позиций"
        !account.authenticated -> "Сохранено на устройстве · требуется вход"
        local?.error != null -> local!!.error!!
        local?.dirty == true -> "Сохранено на устройстве · ожидает отправки"
        local?.serverVersion?.let { it > 0 } == true -> "Синхронизировано"
        permanent -> "Доступна офлайн"
        else -> "Открыта для чтения"
    }
    var marks by remember { mutableStateOf(false) }
    var bookmark by remember { mutableStateOf<ReaderPlace?>(null) }
    var selection by remember { mutableStateOf<BookSelection?>(null) }
    var annotations by remember { mutableStateOf(emptyList<AnnotationEntry>()) }
    var message by remember { mutableStateOf<String?>(null) }
    val jumpTo: (String) -> Unit = { jump = ReaderJump(System.nanoTime(), locator = it, returnable = true) }
    LaunchedEffect(fileId, account.authenticated) {
        if (account.authenticated && copy.format == "epub") {
            try { annotations = data.annotations(scope, fileId) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Opening an offline copy does not depend on annotations. */ }
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        BookReader(File(copy.path), copy.format, copy.title, restored.point?.toReader(), settings,
            { coroutine.launch { appearance.save(it) } },
            { p -> sync.record(scope, fileId, ReadingPoint(p.locator, p.percentage, p.chapter), forceNext); forceNext = false },
            onExit, { bookmark = it }, { selection = it }, { marks = true },
            annotations.filter { it.quote.isNotEmpty() }.map { it.locator }, status, jump,
            onProfile = { night -> coroutine.launch { appearance.selectProfile(night) } })
    }
    val row = local
    if (row?.conflictVersion != null) {
        var resolving by remember { mutableStateOf(false) }
        fun resolve(useLocal: Boolean) {
            if (resolving) return
            resolving = true
            coroutine.launch {
                try {
                    val point = sync.resolve(scope, fileId, useLocal)
                    if (!useLocal) jump = ReaderJump(System.nanoTime(), locator = point?.locator,
                        percentage = if (point == null) 0.0 else null, persist = false)
                } finally { resolving = false }
            }
        }
        AlertDialog(onDismissRequest = {}, title = { Text("Где продолжить чтение?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("На этом устройстве", style = MaterialTheme.typography.titleSmall); Text(description(row.point))
                Text("На сервере", style = MaterialTheme.typography.titleSmall); Text(description(row.conflict))
            } }, confirmButton = { TextButton({ resolve(true) }, enabled = !resolving) { Text("Продолжить с этого устройства") } },
            dismissButton = { TextButton({ resolve(false) }, enabled = !resolving) { Text("Перейти к позиции сервера") } })
    } else alternate?.let { other ->
        AlertDialog(onDismissRequest = { alternate = null }, title = { Text("Позиция другого файла") },
            text = { Text("Вы читали ${other.format.uppercase()} №${other.fileId} до ${percent(other.percentage)}. Перейти примерно к этому месту?") },
            confirmButton = { TextButton({ forceNext = true; jump = ReaderJump(System.nanoTime(), percentage = other.percentage); alternate = null }) { Text("Перейти") } },
            dismissButton = { TextButton({ alternate = null }) { Text("Остаться здесь") } })
    }
    bookmark?.let { point ->
        MarkEditor("Добавить закладку", point.chapter ?: point.locator.toIntOrNull()?.let { "Страница $it" } ?: "Закладка",
            "Название", 200, { bookmark = null }) { label ->
            data.addBookmark(scope, fileId, point.locator, label.ifBlank { "Закладка" })
            bookmark = null; message = "Закладка сохранена на сервере"
        }
    }
    selection?.let { range ->
        MarkEditor("Сохранить выделение", "", "Заметка", 10000, { selection = null }, range.text) { note ->
            val item = data.addAnnotation(scope, fileId, range.cfiRange, range.text, note)
            annotations = listOf(item) + annotations
            selection = null; message = "Выделение сохранено на сервере"
        }
    }
    if (marks) MarksDialog(data, scope, fileId, copy.format, { marks = false }, { marks = false; jumpTo(it) }, { annotations = it })
    message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) },
        confirmButton = { TextButton({ message = null }) { Text("Готово") } }) }
}

@Composable private fun MarkEditor(title: String, initial: String, label: String, limit: Int, close: () -> Unit,
                                    quote: String? = null, save: suspend (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutine = rememberCoroutineScope()
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Column {
        quote?.let { Text(it.take(320), style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(value, { value = it.take(limit) }, label = { Text(label) })
        error?.let { Text("Не синхронизировано. $it", color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = {
        busy = true; coroutine.launch { try { save(value) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendly(e) } finally { busy = false } }
    }, enabled = !busy) { Text("Сохранить") } }, dismissButton = { TextButton(close) { Text("Отмена") } })
}

@Composable private fun MarksDialog(data: PolkaData, scope: String, fileId: Long, format: String, close: () -> Unit,
                                    jump: (String) -> Unit, updated: (List<AnnotationEntry>) -> Unit) {
    var bookmarks by remember { mutableStateOf(emptyList<Bookmark>()) }
    var notes by remember { mutableStateOf(emptyList<AnnotationEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<AnnotationEntry?>(null) }
    val coroutine = rememberCoroutineScope()
    LaunchedEffect(refresh) {
        error = null; loading = true
        try {
            bookmarks = data.bookmarks(scope, fileId)
            if (format == "epub") { notes = data.annotations(scope, fileId); updated(notes) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = friendly(e) }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = close, title = { Text(if (format == "epub") "Закладки и заметки" else "Закладки") },
        text = { LazyColumn {
            if (loading) item { LinearProgressIndicator() }
            error?.let { item { Notice(it) { refresh++ } } }
            if (!loading && error == null && bookmarks.isEmpty() && notes.isEmpty()) item { Text("На сервере пока нет записей.") }
            items(bookmarks) { item -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton({ jump(item.locator) }, Modifier.weight(1f)) { Text(item.label) }
                TextButton({ coroutine.launch { try { data.deleteBookmark(scope, fileId, item.id); refresh++ }
                    catch (e: Exception) { error = friendly(e) } } }) { Text("Удалить") }
            } }
            items(notes) { note -> Column {
                TextButton({ jump(note.locator) }) { Text(note.quote.take(120).ifBlank { "К позиции заметки" }) }
                if (note.note.isNotEmpty()) Text(note.note)
                Row { TextButton({ edit = note }) { Text("Изменить") }
                    TextButton({ coroutine.launch { try { data.deleteAnnotation(scope, fileId, note.id); refresh++ }
                        catch (e: Exception) { error = friendly(e) } } }) { Text("Удалить") } }
            } }
        } }, confirmButton = { TextButton(close) { Text("Закрыть") } })
    edit?.let { note -> MarkEditor("Изменить заметку", note.note, "Заметка", 10000, { edit = null }) {
        data.updateAnnotation(scope, fileId, note.id, it); edit = null; refresh++
    } }
}
