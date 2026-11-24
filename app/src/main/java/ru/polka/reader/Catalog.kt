package ru.polka.reader

import android.text.Html
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
import ru.polka.reader.sync.ProgressSync
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale

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
                        download: (Book, Edition, BookFile) -> Unit, cancel: (Long) -> Unit, read: (Long, Long?) -> Unit) {
    val coroutine = rememberCoroutineScope()
    var book by remember { mutableStateOf<Book?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var job by rememberSaveable { mutableStateOf<Long?>(null) }
    var jobStatus by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var editionId by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    val sync = ProgressSync.get(LocalContext.current)
    LaunchedEffect(book?.id, refresh) {
        // Use the server's per-file position without marking a catalog visit as reading.
        book?.editions?.flatMap { it.files }?.filter { it.readable }?.forEach { sync.preload(scope, it.id) }
    }
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
                    // Conversion can return an existing, identical file from another work in the library.
                    val updated = data.bookForFile(scope, target)
                    val edition = updated.editions.firstOrNull { ed -> ed.files.any { it.id == target && it.readable } }
                        ?: error("Сервер пока не вернул файл после конвертации")
                    if (updated.id == id) book = updated
                    job = null
                    read(edition.files.first { it.id == target }.id, updated.id)
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
        val edition = selected.editions.find { it.id == editionId } ?: selected.editions.firstOrNull()
        val heading: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.title, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                Text(selected.authors.ifBlank { "Автор не указан" }, style = MaterialTheme.typography.titleMedium)
                edition?.let { Text(it.label, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth
            val cover: @Composable () -> Unit = {
                val coverWidth = if (width >= 480.dp) 156.dp else 120.dp
                Cover(data, scope, edition?.takeIf { it.hasCover }?.id, selected.title,
                    Modifier.width(coverWidth).height(coverWidth * 1.44f), ContentScale.Fit)
            }
            if (width < 300.dp || LocalDensity.current.fontScale > 1.5f) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { cover(); heading() }
            } else Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                cover()
                Box(Modifier.weight(1f)) { heading() }
            }
        }
        if (selected.editions.size > 1) {
            var menu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton({ menu = true }) { Text("Выбрать издание · ${selected.editions.size}") }
                DropdownMenu(menu, { menu = false }) {
                    selected.editions.forEachIndexed { index, item ->
                        DropdownMenuItem(text = { Text("${index + 1}. ${item.label}") }, onClick = {
                            editionId = item.id
                            menu = false
                        })
                    }
                }
            }
        }
        val summary = remember(edition?.summary) {
            val value = edition?.summary.orEmpty()
            if (Regex("""</?(?:p|br|div|span|em|strong|b|i|ul|li)(?:\s[^>]*)?\s*/?>""", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
                Html.fromHtml(value, Html.FROM_HTML_MODE_COMPACT).toString().trim()
            } else value
        }
        var expanded by rememberSaveable(edition?.id, summary) { mutableStateOf(false) }
        var canExpand by remember(edition?.id, summary) { mutableStateOf(false) }
        Text("Аннотация", style = MaterialTheme.typography.titleMedium)
        Text(summary.ifBlank { "Описание пока не добавлено." }, style = MaterialTheme.typography.bodyLarge,
            maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) canExpand = it.hasVisualOverflow })
        if (expanded || canExpand) {
            TextButton({ expanded = !expanded }) { Text(if (expanded) "Свернуть" else "Читать полностью") }
        }
        HorizontalDivider()
        if (jobStatus.isNotBlank()) Text(jobStatus)
        if (edition != null) {
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
                            if (file.readable) {
                                if (copy != null) Text("Доступна офлайн")
                                transfer?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button({ read(file.id, selected.id) }) { Text("Читать") }
                                    if (copy == null && (transfer == null || transfer.error != null)) {
                                        OutlinedButton({ download(selected, edition, file) }) {
                                            Text(if (transfer?.error != null) "Повторить загрузку" else "Скачать")
                                        }
                                    }
                                }
                                if (copy == null && transfer != null && transfer.error == null) {
                                    LinearProgressIndicator(progress = { (transfer.bytes.toDouble() / transfer.total.coerceAtLeast(1)).toFloat() })
                                    Text("${sizeLabel(transfer.bytes)} из ${sizeLabel(transfer.total)}")
                                    TextButton({ cancel(file.id) }) { Text("Отменить") }
                                }
                            } else if (derived != null) {
                                Button({ read(derived.id, selected.id) }) { Text("Открыть копию ${derived.format.uppercase()} №${derived.id}") }
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
            if (edition.files.isEmpty()) Text("Для этого издания пока нет файлов.")
        } else Text("Для этого произведения пока нет изданий.")
    }
}
