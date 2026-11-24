package ru.polka.reader.reader

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt

data class ReaderPlace(val locator: String, val percentage: Double, val chapter: String? = null)
data class ReaderJump(val id: Long, val locator: String? = null, val percentage: Double? = null, val persist: Boolean = true)
data class BookSelection(val cfiRange: String, val text: String)
data class BookSettings(val fontPercent: Int = 110, val lineHeight: Float = 1.5f, val marginDp: Int = 16, val theme: String = "light")
internal data class Chapter(val label: String, val href: String, val depth: Int)
internal data class ReaderAction(val type: String, val locator: String? = null, val percentage: Double? = null, val persist: Boolean = true)

@Composable
fun BookReader(
    file: File, format: String, title: String, initial: ReaderPlace?, settings: BookSettings,
    onSettings: (BookSettings) -> Unit, onPosition: (ReaderPlace) -> Unit, onExit: () -> Unit,
    onBookmark: (ReaderPlace) -> Unit, onSelection: (BookSelection) -> Unit, onShowMarks: () -> Unit,
    highlights: List<String> = emptyList(), status: String = "", jump: ReaderJump? = null,
) = key(file.absolutePath) {
    val pdf = format.equals("pdf", ignoreCase = true)
    val accessibility = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    var panels by rememberSaveable { mutableStateOf(true) }
    var menu by rememberSaveable { mutableStateOf("") }
    var chapters by remember { mutableStateOf(emptyList<Chapter>()) }
    var totalPages by remember { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var place by remember { mutableStateOf<ReaderPlace?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastJump by rememberSaveable { mutableStateOf<Long?>(null) }
    var control by remember { mutableStateOf<((ReaderAction) -> Unit)?>(null) }
    val currentPositionCallback by rememberUpdatedState(onPosition)
    val showPlace: (ReaderPlace, Boolean) -> Unit = { value, persist ->
        place = value
        if (persist) currentPositionCallback(value)
    }
    LaunchedEffect(jump?.id, place != null, control != null) {
        if (jump != null && jump.id != lastJump && place != null && control != null) {
            control?.invoke(ReaderAction("jump", jump.locator, jump.percentage, jump.persist))
            lastJump = jump.id
        }
    }
    BackHandler { if (menu.isNotEmpty()) menu = "" else onExit() }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val sideContents = maxWidth >= 840.dp && menu == "contents"
        Row(Modifier.fillMaxSize()) {
            if (sideContents) {
                Surface(Modifier.width(272.dp).fillMaxHeight(), tonalElevation = 2.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Оглавление", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            Tool(Icons.Default.Close, "Закрыть оглавление") { menu = "" }
                        }
                        ChapterList(chapters) { control?.invoke(ReaderAction("jump", it.href)) }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                val tap = { panels = !panels }
                if (pdf) {
                    PdfPane(file, initial, zoom, { zoom = it }, { control = it }, showPlace,
                        { totalPages = it }, tap, { error = it })
                } else {
                    EpubPane(file, initial, settings, highlights, { control = it }, showPlace,
                        { chapters = it }, onSelection, tap, { error = it })
                }
                if (place == null && error == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                if (panels) {
                    Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter), tonalElevation = 4.dp) {
                        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tool(Icons.AutoMirrored.Filled.ArrowBack, "Выйти из книги", true, onExit)
                            Text(title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            Tool(Icons.Default.VisibilityOff, "Скрыть панели") { panels = false }
                        }
                    }
                    Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), tonalElevation = 4.dp) {
                        Column(Modifier.padding(horizontal = 8.dp)) {
                            val point = place
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Tool(Icons.AutoMirrored.Filled.ArrowBack, "Предыдущая страница", point != null) {
                                    control?.invoke(ReaderAction("previous"))
                                }
                                val location = if (pdf && point != null) "Стр. ${point.locator} из $totalPages"
                                    else point?.chapter.orEmpty()
                                Text(listOf(location, point?.let { "${(it.percentage * 100).roundToInt()}%" }.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Tool(Icons.AutoMirrored.Filled.ArrowForward, "Следующая страница", point != null) {
                                    control?.invoke(ReaderAction("next"))
                                }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.SpaceEvenly) {
                                if (pdf) {
                                    Tool(Icons.Default.FindInPage, "Перейти к странице", point != null) { menu = "page" }
                                    Tool(Icons.Default.ZoomOut, "Уменьшить масштаб", zoom > 0.65f) { zoom = (zoom - .25f).coerceAtLeast(.65f) }
                                    Tool(Icons.Default.ZoomIn, "Увеличить масштаб", zoom < 3f) { zoom = (zoom + .25f).coerceAtMost(3f) }
                                } else {
                                    Tool(Icons.AutoMirrored.Filled.List, "Оглавление") { menu = if (sideContents) "" else "contents" }
                                    Tool(Icons.Default.TextFields, "Настройки текста") { menu = "style" }
                                }
                                Tool(Icons.Default.BookmarkAdd, "Добавить закладку", point != null) { point?.let(onBookmark) }
                                Tool(Icons.Default.Bookmarks, "Закладки и заметки", true, onShowMarks)
                            }
                            if (status.isNotBlank()) Text(status, Modifier.padding(start = 8.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                } else if (accessibility.isTouchExplorationEnabled) {
                    FilledTonalButton({ panels = true }, Modifier.align(Alignment.BottomEnd)) { Text("Панели чтения") }
                }
            }
        }
        if (menu == "contents" && !sideContents) AlertDialog(
            onDismissRequest = { menu = "" }, title = { Text("Оглавление") },
            text = { ChapterList(chapters) { control?.invoke(ReaderAction("jump", it.href)); menu = "" } },
            confirmButton = { TextButton({ menu = "" }) { Text("Закрыть") } },
        )
    }
    if (menu == "style") StyleDialog(settings, onSettings) { menu = "" }
    if (menu == "page") {
        var value by remember { mutableStateOf(place?.locator.orEmpty()) }
        val page = value.toIntOrNull()
        AlertDialog(onDismissRequest = { menu = "" }, title = { Text("Перейти к странице") },
            text = { OutlinedTextField(value, { value = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Страница от 1 до $totalPages") }) },
            confirmButton = { TextButton({ control?.invoke(ReaderAction("jump", page.toString())); menu = "" },
                enabled = page != null && page in 1..totalPages) { Text("Перейти") } },
            dismissButton = { TextButton({ menu = "" }) { Text("Отмена") } })
    }
    error?.let {
        AlertDialog(onDismissRequest = { error = null; if (place == null) onExit() },
            title = { Text("Ошибка чтения") }, text = { Text(it) },
            confirmButton = { TextButton({ error = null; if (place == null) onExit() }) { Text("Закрыть") } })
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(action, enabled = enabled) { Icon(icon, label) }
}

@Composable
private fun ChapterList(chapters: List<Chapter>, select: (Chapter) -> Unit) {
    if (chapters.isEmpty()) Text("В этом EPUB нет оглавления.")
    else LazyColumn {
        items(chapters) { item ->
            TextButton(onClick = { select(item) }, modifier = Modifier.fillMaxWidth()) {
                Text(item.label, Modifier.fillMaxWidth().padding(start = (item.depth.coerceAtMost(4) * 12).dp))
            }
        }
    }
}

@Composable
private fun StyleDialog(settings: BookSettings, update: (BookSettings) -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("Настройки текста") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Stepper("Размер шрифта · ${settings.fontPercent}%", settings.fontPercent > 75, settings.fontPercent < 225,
                    { update(settings.copy(fontPercent = (settings.fontPercent - 10).coerceAtLeast(75))) },
                    { update(settings.copy(fontPercent = (settings.fontPercent + 10).coerceAtMost(225))) })
                Stepper("Интервал · " + String.format(java.util.Locale.getDefault(), "%.1f", settings.lineHeight),
                    settings.lineHeight > 1f, settings.lineHeight < 2.2f,
                    { update(settings.copy(lineHeight = (settings.lineHeight - .1f).coerceAtLeast(1f))) },
                    { update(settings.copy(lineHeight = (settings.lineHeight + .1f).coerceAtMost(2.2f))) })
                Stepper("Поля · ${settings.marginDp}", settings.marginDp > 4, settings.marginDp < 40,
                    { update(settings.copy(marginDp = (settings.marginDp - 4).coerceAtLeast(4))) },
                    { update(settings.copy(marginDp = (settings.marginDp + 4).coerceAtMost(40))) })
                Text("Тема страницы", style = MaterialTheme.typography.titleSmall)
                listOf("light" to "Светлая", "sepia" to "Сепия", "dark" to "Тёмная").forEach { (value, label) ->
                    FilterChip(selected = settings.theme == value, onClick = { update(settings.copy(theme = value)) }, label = { Text(label) })
                }
            }
        }, confirmButton = { TextButton(close) { Text("Готово") } })
}

@Composable
private fun Stepper(label: String, minus: Boolean, plus: Boolean, decrease: () -> Unit, increase: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Tool(Icons.Default.Remove, "Уменьшить · $label", minus, decrease)
        Tool(Icons.Default.Add, "Увеличить · $label", plus, increase)
    }
}
