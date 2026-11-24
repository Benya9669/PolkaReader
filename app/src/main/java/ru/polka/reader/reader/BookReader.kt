package ru.polka.reader.reader

import android.content.Context
import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt

data class ReaderPlace(val locator: String, val percentage: Double, val chapter: String? = null)
data class ReaderJump(val id: Long, val locator: String? = null, val percentage: Double? = null,
                      val persist: Boolean = true, val returnable: Boolean = false)
data class BookSelection(val cfiRange: String, val text: String)
data class BookSettings(
    val fontPercent: Int = 110, val lineHeight: Float = 1.5f, val marginDp: Int = 16,
    val theme: String = "light", val pageTurn: String = "slide", val spread: Boolean = true, val profile: String = "day",
    val fontFamily: String = "book",
)
internal data class Chapter(val label: String, val href: String, val depth: Int)
internal data class ReaderAction(val type: String, val locator: String? = null, val percentage: Double? = null, val persist: Boolean = true)

@Composable
fun BookReader(
    file: File, format: String, title: String, initial: ReaderPlace?, settings: BookSettings,
    onSettings: (BookSettings) -> Unit, onPosition: (ReaderPlace) -> Unit, onExit: () -> Unit,
    onBookmark: (ReaderPlace) -> Unit, onSelection: (BookSelection) -> Unit, onShowMarks: () -> Unit,
    highlights: List<String> = emptyList(), status: String = "", jump: ReaderJump? = null,
    onProfile: (Boolean) -> Unit,
) = key(file.absolutePath) {
    val pdf = format.equals("pdf", ignoreCase = true)
    val accessibility = LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val motionEnabled = systemMotionEnabled()
    val effectiveSettings = if (motionEnabled) settings else settings.copy(pageTurn = "none")
    var panels by rememberSaveable { mutableStateOf(true) }
    var menu by rememberSaveable { mutableStateOf("") }
    var chapters by remember { mutableStateOf(emptyList<Chapter>()) }
    var totalPages by remember { mutableIntStateOf(0) }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var place by remember { mutableStateOf<ReaderPlace?>(null) }
    var returnLocator by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastJump by rememberSaveable { mutableStateOf<Long?>(null) }
    var control by remember { mutableStateOf<((ReaderAction) -> Unit)?>(null) }
    val currentPositionCallback by rememberUpdatedState(onPosition)
    val showPlace: (ReaderPlace, Boolean) -> Unit = { value, persist ->
        place = value
        if (persist) currentPositionCallback(value)
    }
    fun rememberOrigin(origin: ReaderPlace?) {
        if (returnLocator == null && origin != null) returnLocator = origin.locator
        panels = true
    }
    fun goTo(locator: String) {
        if (locator != place?.locator) rememberOrigin(place)
        control?.invoke(ReaderAction("jump", locator))
    }
    fun goBackToReading() {
        val target = returnLocator ?: return
        control?.invoke(ReaderAction("jump", target))
        returnLocator = null
        menu = ""
    }
    LaunchedEffect(jump?.id, place != null, control != null) {
        if (jump != null && jump.id != lastJump && place != null && control != null) {
            if (jump.returnable && jump.locator != place?.locator) rememberOrigin(place)
            control?.invoke(ReaderAction("jump", jump.locator, jump.percentage, jump.persist))
            lastJump = jump.id
        }
    }
    BackHandler {
        when {
            menu.isNotEmpty() -> menu = ""
            returnLocator != null -> goBackToReading()
            else -> onExit()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val paperPanelHeight = maxHeight * 0.48f
        val sideContents = maxWidth >= 840.dp && menu == "contents"
        val wideLandscape = maxWidth > maxHeight && maxWidth >= 840.dp && maxHeight >= 440.dp
        val spread = settings.spread && wideLandscape && (!sideContents || maxWidth - 272.dp >= 840.dp)
        Row(Modifier.fillMaxSize()) {
            if (sideContents) {
                Surface(Modifier.width(272.dp).fillMaxHeight(), tonalElevation = 2.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Оглавление", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            Tool(Icons.Default.Close, "Закрыть оглавление") { menu = "" }
                        }
                        ChapterList(chapters) { goTo(it.href) }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                val tap = { panels = !panels }
                if (pdf) {
                    PdfPane(file, initial, zoom, effectiveSettings.pageTurn, spread, { zoom = it }, { control = it }, showPlace,
                        { totalPages = it }, tap, { error = it })
                } else {
                    EpubPane(file, initial, effectiveSettings, highlights, { control = it }, showPlace,
                        { chapters = it }, onSelection, tap, { error = it }, spread = spread,
                        onLinkNavigate = { rememberOrigin(it) })
                }
                if (place == null && error == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = panels && menu != "style", modifier = Modifier.matchParentSize(),
                    enter = if (motionEnabled) fadeIn(tween(130)) else EnterTransition.None,
                    exit = if (motionEnabled) fadeOut(tween(100)) else ExitTransition.None,
                ) {
                  Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxWidth().align(Alignment.TopCenter), tonalElevation = 4.dp) {
                        Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Tool(Icons.AutoMirrored.Filled.ArrowBack, "Выйти из книги", true, onExit)
                            Text(title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            Tool(Icons.Default.AutoStories, "Перелистывание") { menu = "turn" }
                            Tool(Icons.Default.VisibilityOff, "Скрыть панели") { panels = false }
                        }
                    }
                    Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), tonalElevation = 4.dp) {
                        Column(Modifier.padding(horizontal = 8.dp)) {
                            val point = place
                            if (returnLocator != null) {
                                TextButton(::goBackToReading, enabled = point != null) { Text("Вернуться к месту чтения") }
                            }
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
                  }
                }
                if (!panels && accessibility.isTouchExplorationEnabled) {
                    FilledTonalButton({ panels = true }, Modifier.align(Alignment.BottomEnd)) { Text("Панели чтения") }
                }
                if (menu == "style") {
                    PaperControls(settings, onSettings, onProfile, { menu = "" },
                        Modifier.align(Alignment.BottomCenter).widthIn(max = 580.dp).fillMaxWidth().heightIn(max = paperPanelHeight))
                }
            }
        }
        if (menu == "contents" && !sideContents) AlertDialog(
            onDismissRequest = { menu = "" }, title = { Text("Оглавление") },
            text = { ChapterList(chapters) { goTo(it.href); menu = "" } },
            confirmButton = { TextButton({ menu = "" }) { Text("Закрыть") } },
        )
    }
    if (menu == "turn") {
        AlertDialog(onDismissRequest = { menu = "" }, title = { Text("Перелистывание") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    for ((mode, label) in listOf("slide" to "Сдвиг", "curl" to "Загиб страницы", "none" to "Без анимации")) {
                        val selected = settings.pageTurn == mode
                        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton,
                            onClick = { onSettings(settings.copy(pageTurn = mode)) }).heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label)
                        }
                    }
                    if (!motionEnabled) Text("Анимация отключена в настройках Android.",
                        style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Две страницы на планшете в альбомной ориентации", Modifier.weight(1f))
                        Switch(checked = settings.spread, onCheckedChange = { onSettings(settings.copy(spread = it)) },
                            modifier = Modifier.semantics { contentDescription = "Две страницы" })
                    }
                }
            },
            confirmButton = { TextButton({ menu = "" }) { Text("Готово") } })
    }
    if (menu == "page") {
        var value by remember { mutableStateOf(place?.locator.orEmpty()) }
        val page = value.toIntOrNull()
        AlertDialog(onDismissRequest = { menu = "" }, title = { Text("Перейти к странице") },
            text = { OutlinedTextField(value, { value = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text("Страница от 1 до $totalPages") }) },
            confirmButton = { TextButton({ goTo(page.toString()); menu = "" },
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
private fun systemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
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
private fun PaperControls(settings: BookSettings, update: (BookSettings) -> Unit, profile: (Boolean) -> Unit,
                          close: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Настройки текста", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(close) { Text("Готово") }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = settings.profile == "day", onClick = { profile(false) }, label = { Text("День") })
                    FilterChip(selected = settings.profile == "night", onClick = { profile(true) }, label = { Text("Перед сном") })
                }
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
                Text("Шрифт", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("book" to "Из книги", "serif" to "С засечками", "sans-serif" to "Без засечек").forEach { (family, label) ->
                        FilterChip(selected = settings.fontFamily == family, onClick = { update(settings.copy(fontFamily = family)) },
                            label = { Text(label) })
                    }
                }
                Text("Тема страницы", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("light" to "Светлая", "sepia" to "Сепия", "dark" to "Тёмная").forEach { (value, label) ->
                        FilterChip(selected = settings.theme == value, onClick = { update(settings.copy(theme = value)) }, label = { Text(label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, minus: Boolean, plus: Boolean, decrease: () -> Unit, increase: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Tool(Icons.Default.Remove, "Уменьшить · $label", minus, decrease)
        Tool(Icons.Default.Add, "Увеличить · $label", plus, increase)
    }
}
