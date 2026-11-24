package ru.polka.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.polka.reader.data.*
import ru.polka.reader.sync.ProgressRow

@Composable
internal fun Catalog(data: PolkaData, scope: String, libraries: List<Library>, refresh: Int, select: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var library by rememberSaveable { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var retry by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<BookPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, library, page, refresh, retry) {
        error = null
        result = null
        if (query.isNotBlank()) delay(280)
        try { result = data.catalog(scope, query, library, page) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = friendly(e) }
    }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(query, { query = it.take(300); page = 1 }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Название или автор") }, singleLine = true)
            Box {
                TextButton({ menu = true }) { Text((libraries.find { it.id == library }?.name ?: "Все библиотеки") + " ▾") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Все библиотеки") }, onClick = { library = null; page = 1; menu = false })
                    libraries.forEach { lib ->
                        DropdownMenuItem(text = { Text(lib.name) }, onClick = { library = lib.id; page = 1; menu = false })
                    }
                }
            }
        }
        if (error != null) item { Notice(error!!) { retry++ } }
        val current = result
        if (current == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (current?.cached == true) item { Text("Сохранённый каталог · офлайн") }
        if (current?.items?.isEmpty() == true) item { Text("Книги не найдены.") }
        items(current?.items.orEmpty(), key = { it.id }) { book ->
            OutlinedCard(onClick = { select(book.id) }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Cover(data, scope, book.editions.firstOrNull { it.hasCover }?.id, book.title, Modifier.size(44.dp, 64.dp))
                    Column {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif)
                        Text(book.authors, style = MaterialTheme.typography.bodyMedium)
                        Text(book.editions.flatMap { it.files }.map { it.format.uppercase() }.distinct().joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (current != null && (page > 1 || current.hasNext)) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ page-- }, enabled = page > 1) { Text("Назад") }
                Text("Страница $page")
                TextButton({ page++ }, enabled = current.hasNext) { Text("Далее") }
            }
        }
    }
}

@Composable
internal fun BookDetail(data: PolkaData, scope: String, id: Long, libraries: List<Library>, downloads: List<Download>,
                        positions: List<ProgressRow>, transfers: Map<Long, Transfer>, refresh: Int,
                        download: (Book, Edition, BookFile) -> Unit, cancel: (Long) -> Unit, read: (Long) -> Unit,
                        downloadAndRead: (Book, Edition, BookFile) -> Unit) {
    val coroutine = rememberCoroutineScope()
    var book by remember { mutableStateOf<Book?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var job by rememberSaveable { mutableStateOf<Long?>(null) }
    var jobStatus by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(id, refresh, retry) {
        error = null
        try { book = data.book(scope, id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = friendly(e) }
    }
    LaunchedEffect(job, retry) {
        val jobId = job ?: return@LaunchedEffect
        try {
            while (true) {
                val result = data.conversion(scope, jobId) ?: error("Задача конвертации недоступна")
                jobStatus = when (result.status) {
                    "queued" -> "В очереди"
                    "running" -> "Выполняется конвертация"
                    "done" -> "Готово"
                    "error" -> result.error ?: "Ошибка конвертации"
                    else -> result.status
                }
                val target = result.fileId
                if (target != null) {
                    val updated = data.book(scope, id)
                    val edition = updated.editions.firstOrNull { ed -> ed.files.any { it.id == target && it.readable } }
                        ?: error("Сервер пока не вернул файл после конвертации")
                    book = updated
                    job = null
                    downloadAndRead(updated, edition, edition.files.first { it.id == target })
                    break
                }
                if (result.status in setOf("error", "failed", "done", "completed")) { job = null; break }
                delay(1800)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = friendly(e) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { Notice(it) { retry++ } }
        val selected = book
        if (selected == null) {
            if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        Text(selected.title, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
        Text(selected.authors, style = MaterialTheme.typography.titleMedium)
        if (jobStatus.isNotBlank()) Text(jobStatus)
        selected.editions.forEach { edition ->
            HorizontalDivider()
            Text(edition.label, style = MaterialTheme.typography.titleMedium)
            if (edition.summary.isNotBlank()) Text(edition.summary, maxLines = 8, overflow = TextOverflow.Ellipsis)
            edition.files.forEach { file ->
                key(file.id) {
                    val copy = downloads.find { it.fileId == file.id }
                    val transfer = transfers[file.id]
                    val position = positions.find { it.fileId == file.id }
                    val derived = edition.files.firstOrNull { it.derivedFrom == file.id && it.readable }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall)
                            Text("${file.format.uppercase()} · №${file.id} · ${sizeLabel(file.size)}", style = MaterialTheme.typography.bodySmall)
                            file.derivedFrom?.let { Text("Читаемая копия файла №$it") }
                            if (position != null) Text("Место чтения · ${placeLabel(position.point)}")
                            if (copy != null) {
                                Text("Доступна офлайн")
                                Button({ read(file.id) }) { Text("Читать") }
                            } else if (transfer != null && transfer.error == null) {
                                LinearProgressIndicator(progress = { (transfer.bytes.toDouble() / transfer.total.coerceAtLeast(1)).toFloat() })
                                Text("${sizeLabel(transfer.bytes)} из ${sizeLabel(transfer.total)}")
                                TextButton({ cancel(file.id) }) { Text("Отменить") }
                            } else if (file.readable) {
                                transfer?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button({ download(selected, edition, file) }) { Text(if (transfer?.error != null) "Повторить загрузку" else "Скачать") }
                            } else if (derived != null) {
                                TextButton({
                                    if (downloads.any { it.fileId == derived.id }) read(derived.id)
                                    else downloadAndRead(selected, edition, derived)
                                }) { Text("Открыть копию ${derived.format.uppercase()} №${derived.id}") }
                            } else if (file.conversionTarget != null && libraries.any { it.id == selected.libraryId && it.canConvert }) {
                                OutlinedButton(onClick = {
                                    starting = true
                                    coroutine.launch {
                                        try { job = data.startConversion(scope, file.id).id; jobStatus = "В очереди" }
                                        catch (e: CancellationException) { throw e } catch (e: Exception) { error = friendly(e) }
                                        finally { starting = false }
                                    }
                                }, enabled = job == null && !starting) { Text("Создать ${file.conversionTarget}") }
                            } else Text("Для чтения нужна копия EPUB или PDF.")
                        }
                    }
                }
            }
        }
    }
}
