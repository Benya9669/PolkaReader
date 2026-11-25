package ru.polka.reader.reader

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import ru.polka.reader.data.ReaderSource
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ReadingDesk(context: Context, private val key: String) {
    private val prefs = context.getSharedPreferences("reader_places", Context.MODE_PRIVATE)
    var reference by mutableStateOf(prefs.getString("$key/reference", "").orEmpty())
        private set
    var line by mutableStateOf(prefs.getString("$key/line", "").orEmpty())
        private set
    fun pin(locator: String) { reference = locator; prefs.edit().putString("$key/reference", locator).apply() }
    fun mark(locator: String) { line = locator; prefs.edit().putString("$key/line", locator).apply() }
}

@Composable internal fun rememberReadingDesk(key: String): ReadingDesk {
    val context = LocalContext.current
    return remember(key) { ReadingDesk(context.applicationContext, key) }
}

@Composable internal fun ReaderWindow(brightness: Int, minutes: Int, interaction: Long) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window, brightness) {
        val prior = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = if (brightness < 0) -1f else (brightness / 100f).coerceIn(.01f, 1f) }
        onDispose { window.attributes = window.attributes.apply { screenBrightness = prior } }
    }
    val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    LaunchedEffect(window, minutes, interaction) {
        if (minutes == 0) return@LaunchedEffect
        val previous = window.attributes.flags and flag != 0
        window.addFlags(flag)
        try {
            if (minutes > 0) delay(minutes * 60_000L) else kotlinx.coroutines.awaitCancellation()
        } finally { if (!previous) window.clearFlags(flag) }
    }
}

internal fun Modifier.readerWindowGestures(brightness: Int, update: (Int) -> Unit, save: (Int) -> Unit,
                                          interact: () -> Unit): Modifier = pointerInput(brightness) {
    awaitPointerEventScope {
        var tracking = false
        var sliding = false
        var originY = 0f
        var originX = 0f
        var value = 50
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val p = event.changes.first()
            if (p.pressed && !p.previousPressed) {
                interact()
                tracking = p.position.x < 22.dp.toPx()
                originY = p.position.y; originX = p.position.x; sliding = false
            }
            if (event.changes.size > 1) tracking = false
            if (!tracking) continue
            val dy = originY - p.position.y
            if (!sliding && abs(dy) > 20.dp.toPx() && abs(dy) > abs(p.position.x - originX) * 2) sliding = true
            if (sliding) {
                value = ((if (brightness < 0) 50 else brightness) + dy * 110 / size.height.coerceAtLeast(1)).roundToInt().coerceIn(0,100)
                update(value)
                p.consume()
            }
            if (!p.pressed) { if (sliding) save(value); tracking = false }
        }
    }
}

@Composable internal fun ComfortDialog(s: BookSettings, pdf: Boolean, change: (BookSettings) -> Unit,
                                       close: () -> Unit, pin: () -> Unit, reopen: (() -> Unit)?) {
    AlertDialog(onDismissRequest = close, title = { Text("Удобство чтения") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (pdf) {
                Choice("Читать по двум колонкам", s.columns) { change(s.copy(columns = it)) }
                Choice("Обложка отдельной страницей", s.coverAlone) { change(s.copy(coverAlone = it)) }
            } else {
                Choice("Непрерывная прокрутка", s.scrollBook) { change(s.copy(scrollBook = it)) }
                Choice("Автоматические переносы", s.hyphenate) { change(s.copy(hyphenate = it)) }
                Text("Выравнивание")
                for ((key,label) in listOf("book" to "Как в книге","start" to "По началу строки","justify" to "По ширине"))
                    FilterChip(s.alignment == key, { change(s.copy(alignment = key)) }, { Text(label) })
            }
            Text("Яркость " + if (s.brightness < 0) "системная" else "${s.brightness}%")
            var light by remember(s.brightness) { mutableFloatStateOf(if (s.brightness < 0) 50f else s.brightness.toFloat()) }
            Slider(light, { light = it }, valueRange = 0f..100f, onValueChangeFinished = { change(s.copy(brightness = light.roundToInt())) })
            TextButton({ change(s.copy(brightness = -1)) }) { Text("Системная яркость") }
            Text("Оставлять экран включённым")
            for ((key,label) in listOf(0 to "Как в системе",5 to "5 минут",15 to "15 минут",-1 to "Пока открыта книга"))
                FilterChip(s.timeoutMinutes == key, { change(s.copy(timeoutMinutes = key)) }, { Text(label) })
            TextButton(pin) { Text("Закрепить это место рядом") }
            if (reopen != null) TextButton(reopen) { Text("Открыть закреплённое место") }
        }
    }, confirmButton = { TextButton(close) { Text("Готово") } })
}

@Composable private fun Choice(label: String, selected: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(selected, role = Role.Switch, onValueChange = set).heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f)); Switch(selected,null)
    }
}

@Composable internal fun ReferencePane(file: ReaderSource, pdf: Boolean, locator: String, settings: BookSettings,
                                      crop: PdfCrop, moved: (String) -> Unit, close: () -> Unit) {
    val initial = remember(file) { ReaderPlace(locator, 0.0) }
    var control by remember { mutableStateOf<((ReaderAction) -> Unit)?>(null) }
    var error by remember { mutableStateOf("") }
    Column {
        Row {
            TextButton({ control?.invoke(ReaderAction("previous")) }) { Text("Назад") }
            TextButton({ control?.invoke(ReaderAction("next")) }) { Text("Вперёд") }
            TextButton(close) { Text("К чтению") }
        }
        if (error.isNotBlank()) Text(error)
        val position: (ReaderPlace, Boolean) -> Unit = { p, persist -> if (persist) moved(p.locator) }
        if (pdf) PdfPane(file, initial, 1f, "none", false, {}, { control = it }, position, {}, {}, { error = it },
            crop, settings.tapSide, settings.tapZone, {})
        else EpubPane(file, initial, settings, emptyList(), { control = it }, position, {}, {}, {}, { error = it },
            inspection = remember { ReaderInspection() }, onRemainder = {}, onIllustration = {})
    }
}
