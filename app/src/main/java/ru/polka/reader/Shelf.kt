package ru.polka.reader

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import ru.polka.reader.data.*
import ru.polka.reader.sync.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

internal fun percent(value: Double) = "${(value.coerceIn(0.0, 1.0) * 100).roundToInt()} %"
internal fun placeLabel(point: ReadingPoint): String =
    listOfNotNull(point.chapter ?: point.locator.toLongOrNull()?.let { "Страница $it" }, percent(point.percentage)).joinToString(" · ")

private fun timestamp(iso: String?): Long {
    if (iso == null) return 0
    return runCatching { Instant.parse(iso).toEpochMilli() }
        .getOrElse { runCatching { LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrDefault(0) }
}

@Composable
internal fun Shelf(account: Account, data: PolkaData, sync: ProgressSync, transfers: Transfers,
                   appearance: Appearance, theme: String, setTheme: (String) -> Unit, signIn: () -> Unit) {
    val scope = account.scope
    val coroutine = rememberCoroutineScope()
    val downloads by remember(scope) { data.downloads(scope) }.collectAsState(initial = emptyList())
    val positions by remember(scope) { sync.observeRecent(scope) }.collectAsState(initial = emptyList())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by rememberSaveable { mutableStateOf<Long?>(null) }
    var reading by rememberSaveable { mutableStateOf<Long?>(null) }
    var readingWork by rememberSaveable { mutableStateOf<Long?>(null) }
    val open: (Long, Long?) -> Unit = { fileId, workId -> readingWork = workId; reading = fileId }
    var menu by remember { mutableStateOf(false) }
    var config by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var libraryList by remember { mutableStateOf(emptyList<Library>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val progress by transfers.progress.collectAsState()
    LaunchedEffect(scope, refresh, account.authenticated) {
        try { libraryList = data.libraries(scope) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (tab == 1) error = friendly(e) }
    }
    val current = reading
    if (current != null) {
        OpenReader(account, data, sync, current, readingWork, appearance) { reading = null; refresh++ }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 720.dp
        val labels = listOf("Главная", "Библиотека", "На устройстве")
        val icons = listOf(Icons.Outlined.Home, Icons.Outlined.LocalLibrary, Icons.Outlined.FileDownload)
        val choose: (Int) -> Unit = { tab = it; detail = null; error = null }
        val singleDetail = !wide && detail != null
        BackHandler(detail != null || tab != 0) { if (detail != null) detail = null else tab = 0 }
        Row {
            if (wide) NavigationRail(header = {
                Column(Modifier.widthIn(max = 112.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandMark(Modifier.size(38.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("Polka Reader", style = MaterialTheme.typography.labelMedium)
                }
            }) {
                labels.forEachIndexed { i, label -> NavigationRailItem(selected = i == tab, onClick = { choose(i) },
                    icon = { Icon(icons[i], null) }, label = { Text(label) }) }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (singleDetail) IconButton({ detail = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад") }
                    Text(if (singleDetail) "Карточка книги" else labels[tab], Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.AccountCircle, "Учётная запись и оформление") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(account.username) }, onClick = { config = true; menu = false })
                            DropdownMenuItem(text = { Text("Настройки") }, onClick = { config = true; menu = false })
                            DropdownMenuItem(text = { Text("О приложении") }, onClick = { about = true; menu = false })
                            DropdownMenuItem(text = { Text("Сменить аккаунт или сервер") }, onClick = { menu = false; signIn() })
                            DropdownMenuItem(text = { Text("Выйти") }, onClick = { menu = false; coroutine.launch {
                                try { data.logout(scope) } catch (e: Exception) { error = friendly(e) }
                            } })
                        }
                    }
                }
                if (!account.authenticated) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Сеанс истёк. Скачанные книги доступны.", Modifier.weight(1f))
                    TextButton(signIn) { Text("Войти") }
                }
                error?.let { Notice(it) { error = null; refresh++ } }
                Row(Modifier.weight(1f)) {
                    if (!singleDetail) Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> Home(data, account, downloads, positions, refresh, { choose(1) }, open)
                            1 -> Catalog(data, scope, libraryList, refresh, { detail = it })
                            2 -> DeviceDownloads(downloads, positions, { id -> open(id, downloads.find { it.fileId == id }?.workId) }, { id ->
                                coroutine.launch {
                                    try { data.remove(scope, id) } catch (e: Exception) { error = friendly(e) }
                                }
                            }, { choose(1) })
                        }
                    }
                    val selected = detail
                    if (selected != null && (singleDetail || wide)) {
                        if (!singleDetail) VerticalDivider()
                        Box(Modifier.weight(if (wide) 1.15f else 1f)) {
                            key(selected) {
                                BookDetail(data, scope, selected, libraryList, downloads, positions, progress, refresh,
                                    { b, ed, f -> transfers.start(data, scope, b, ed, f) },
                                    transfers::cancel, open)
                            }
                        }
                    }
                }
                if (!wide && !singleDetail) NavigationBar {
                    labels.forEachIndexed { i, label -> NavigationBarItem(selected = tab == i,
                        onClick = { choose(i) }, icon = { Icon(icons[i], null) }, label = { Text(label) }) }
                }
            }
        }
    }
    if (config) AlertDialog(onDismissRequest = { config = false }, title = { Text("Настройки") },
        text = { Column {
            Text(account.username); Text(account.server, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp)); Text("Тема приложения")
            listOf("Системная", "Светлая", "Тёмная").forEach {
                TextButton(onClick = { setTheme(it) }) { Text((if (theme == it) "✓  " else "") + it) }
            }
        } }, confirmButton = { TextButton({ config = false }) { Text("Готово") } })
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("О приложении") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrandMark(Modifier.size(56.dp))
            Text("Polka Reader", style = MaterialTheme.typography.headlineSmall)
            Text("Версия ${BuildConfig.VERSION_NAME}")
            Text("Чтение книг с вашего сервера «Полка».")
        } }, confirmButton = { TextButton({ about = false }) { Text("Закрыть") } })
}

@Composable internal fun Waiting() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
@Composable internal fun Notice(message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(retry) { Text("Повторить") }
    }
}

@Composable internal fun Cover(data: PolkaData, scope: String, editionId: Long?, title: String, modifier: Modifier,
                              scale: ContentScale = ContentScale.Crop) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, scope, editionId) {
        value = null
        if (editionId != null) try {
            val cover = data.cover(scope, editionId)
            value = withContext(Dispatchers.IO) {
                cover?.let {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(it.path, options)
                    options.inSampleSize = Integer.highestOneBit((maxOf(options.outWidth, options.outHeight) / 350).coerceAtLeast(1))
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeFile(it.path, options)?.asImageBitmap()
                }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { value = null }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, "Обложка книги «$title»", Modifier.fillMaxSize(), contentScale = scale) }
            ?: Text(title.trim().take(1), fontFamily = FontFamily.Serif, style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Home(data: PolkaData, account: Account, downloads: List<Download>, rows: List<ProgressRow>, refresh: Int,
                 catalog: () -> Unit, read: (Long, Long?) -> Unit) {
    var remote by remember { mutableStateOf(emptyList<ReadingBook>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(account.scope, refresh, retry) {
        error = null
        try { remote = data.home(account.scope) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = friendly(e) }
    }
    val recent by remember(account.scope) { data.recentBook(account.scope) }.collectAsState(initial = null)
    val opened = (downloads.mapNotNull { copy -> copy.lastOpenedAt?.let { copy.recent(it) } } + listOfNotNull(recent))
        .maxByOrNull { it.openedAt }
    val local = opened?.let { book -> rows.firstOrNull { it.fileId == book.fileId } }
    val latest = remote.maxByOrNull { timestamp(it.updated) }
    val localWins = opened != null && (latest == null || local?.dirty == true ||
        maxOf(opened.openedAt, local?.lastReadAt ?: 0) >= timestamp(latest.updated))
    val id = if (localWins) opened?.fileId else latest?.fileId
    val copy = downloads.firstOrNull { it.fileId == id }
    val title = if (localWins) opened?.title else latest?.book?.title
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Продолжить чтение", style = MaterialTheme.typography.headlineMedium)
        if (id != null && title != null) {
            val pct = if (localWins) local?.point?.percentage ?: 0.0 else latest!!.percentage
            val label = if (localWins) local?.point?.let(::placeLabel) ?: "Начало книги"
                else listOfNotNull(latest?.chapter, percent(pct)).joinToString(" · ")
            val open = { read(id, if (localWins) opened?.workId else latest?.book?.id) }
            val heroPaper = Color(0xfffff4d8)
            val heroInk = Color(0xff294738)
            Surface(Modifier.widthIn(max = 940.dp).fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                color = heroInk, contentColor = heroPaper) {
                BoxWithConstraints(Modifier.padding(20.dp)) {
                    val large = maxWidth >= 580.dp
                    val stack = LocalDensity.current.fontScale > 1.5f && !large
                    val jacket: @Composable () -> Unit = {
                        Cover(data, account.scope, if (localWins) opened?.editionId else latest?.book?.editions?.firstOrNull { ed -> ed.files.any { it.id == id } && ed.hasCover }?.id,
                            title, if (large) Modifier.size(156.dp, 224.dp) else Modifier.size(88.dp, 128.dp))
                    }
                    val details: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(if (large) 12.dp else 6.dp)) {
                            Text(if (copy != null) "${copy.format.uppercase()} · На устройстве" else "В библиотеке",
                                style = MaterialTheme.typography.labelMedium, color = heroPaper)
                            Text(title, style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall)
                            Text(if (localWins) opened?.authors.orEmpty() else latest?.book?.authors.orEmpty())
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            LinearProgressIndicator(progress = { pct.toFloat() }, modifier = Modifier.fillMaxWidth(),
                                color = Color(0xffefb667), trackColor = Color(0xff4e6053))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (stack) { jacket(); details() }
                        else Row(horizontalArrangement = Arrangement.spacedBy(if (large) 28.dp else 14.dp),
                            verticalAlignment = Alignment.CenterVertically) { jacket(); Box(Modifier.weight(1f)) { details() } }
                        Button(open, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(
                            containerColor = heroPaper, contentColor = heroInk)) {
                            Icon(Icons.Outlined.MenuBook, null); Spacer(Modifier.width(10.dp))
                            Text("Продолжить чтение")
                        }
                    }
                }
            }
        } else {
            Text("Здесь появится книга, которую вы читали последней.")
            Button(catalog) { Text("Открыть библиотеку") }
        }
        val others = downloads.filter { it.fileId != id }.take(4)
        if (others.isNotEmpty()) {
            Text("На устройстве", style = MaterialTheme.typography.titleLarge)
            others.forEach { other ->
                Row(Modifier.widthIn(max = 940.dp).fillMaxWidth().clickable { read(other.fileId, other.workId) }.padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cover(data, account.scope, other.editionId, other.title, Modifier.size(38.dp, 54.dp))
                    Column(Modifier.weight(1f)) { Text(other.title, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(other.authors, other.format.uppercase()).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
        error?.let { Notice(it) { retry++ } }
    }
}

@Composable
private fun DeviceDownloads(downloads: List<Download>, positions: List<ProgressRow>, read: (Long) -> Unit,
                            remove: (Long) -> Unit, catalog: () -> Unit) {
    var removing by remember { mutableStateOf<Download?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (downloads.isEmpty()) item { Text("Скачанных книг пока нет."); TextButton(catalog) { Text("Открыть библиотеку") } }
        items(downloads, key = { it.fileId }) { copy ->
            OutlinedCard(onClick = { read(copy.fileId) }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(copy.title, style = MaterialTheme.typography.titleMedium)
                        Text(copy.authors)
                        Text("${copy.format.uppercase()} · №${copy.fileId} · ${sizeLabel(copy.bytes)}", style = MaterialTheme.typography.bodySmall)
                        positions.find { it.fileId == copy.fileId }?.let { Text(placeLabel(it.point)) }
                    }
                    IconButton(onClick = { removing = copy }) { Icon(Icons.Outlined.Delete, "Удалить локальную копию") }
                }
            }
        }
    }
    removing?.let { copy -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Удалить с устройства?") },
        text = { Text("«${copy.title}» останется в библиотеке сервера. Место чтения сохранится.") },
        confirmButton = { TextButton({ remove(copy.fileId); removing = null }) { Text("Удалить") } },
        dismissButton = { TextButton({ removing = null }) { Text("Отмена") } }) }
}

internal fun sizeLabel(bytes: Long): String = if (bytes < 1_048_576) "${(bytes + 1023) / 1024} КБ"
    else String.format(java.util.Locale.forLanguageTag("ru"), "%.1f МБ", bytes / 1_048_576.0)
