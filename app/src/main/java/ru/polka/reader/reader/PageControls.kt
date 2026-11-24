package ru.polka.reader.reader

import android.view.KeyEvent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.polka.reader.MainActivity
import kotlin.math.roundToInt

internal class ReaderVolumeKeys {
    var turn: ((Boolean) -> Boolean)? = null
    private val pressed = mutableSetOf<Int>()

    fun handle(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_DOWN && key != KeyEvent.KEYCODE_VOLUME_UP) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    pressed.remove(key)
                    if (turn?.invoke(key == KeyEvent.KEYCODE_VOLUME_DOWN) == true) pressed.add(key)
                }
                key in pressed
            }
            KeyEvent.ACTION_UP -> pressed.remove(key)
            else -> false
        }
    }
}

@Composable
internal fun VolumePaging(enabled: Boolean, navigate: (Boolean) -> Unit) {
    val activity = LocalActivity.current as? MainActivity ?: return
    val active by rememberUpdatedState(enabled)
    val move by rememberUpdatedState(navigate)
    DisposableEffect(activity) {
        activity.readerVolumeKeys.turn = { next ->
            if (active) move(next)
            active
        }
        onDispose { activity.readerVolumeKeys.turn = null }
    }
}

@Composable
internal fun OneHandControls(settings: BookSettings, update: (BookSettings) -> Unit) {
    Text("Управление одной рукой", style = MaterialTheme.typography.titleMedium)
    Text("Край для перехода вперёд", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((side, label) in listOf("auto" to "По книге", "left" to "Левый", "right" to "Правый")) {
            FilterChip(selected = settings.tapSide == side, onClick = { update(settings.copy(tapSide = side)) },
                label = { Text(label) })
        }
    }
    var zone by remember(settings.tapZone) { mutableFloatStateOf(settings.tapZone.toFloat()) }
    Text("Ширина каждого края · ${zone.roundToInt()}%")
    Slider(value = zone, onValueChange = { zone = it }, valueRange = 12f..36f,
        onValueChangeFinished = { update(settings.copy(tapZone = zone.roundToInt())) },
        modifier = Modifier.semantics { contentDescription = "Ширина зоны касания" })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Кнопки громкости")
            Text("Тише — вперёд, громче — назад", style = MaterialTheme.typography.bodySmall)
        }
        Switch(settings.volumeTurn, { update(settings.copy(volumeTurn = it)) },
            Modifier.semantics { contentDescription = "Листать кнопками громкости" })
    }
}

@Composable
internal fun PdfCropControls(crop: PdfCrop, update: (PdfCrop) -> Unit, sample: (suspend () -> PdfCrop?)?,
                            save: () -> Unit, cancel: () -> Unit, modifier: Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var sampling by remember { mutableStateOf(false) }
    var empty by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    val names = listOf("Слева", "Сверху", "Справа", "Снизу")
    val value = listOf(crop.left, crop.top, crop.right, crop.bottom)[selected]
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Поля этого PDF", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                names.forEachIndexed { index, name ->
                    FilterChip(selected == index, { selected = index }, label = { Text(name) })
                }
            }
            Text("${names[selected]} · $value%")
            Slider(value.toFloat(), onValueChange = { amount ->
                val percent = amount.roundToInt()
                update(when (selected) {
                    0 -> crop.copy(left = percent)
                    1 -> crop.copy(top = percent)
                    2 -> crop.copy(right = percent)
                    else -> crop.copy(bottom = percent)
                })
            }, enabled = !sampling, valueRange = 0f..PdfCrop.MAX.toFloat(),
                modifier = Modifier.semantics { contentDescription = "Обрезка поля: ${names[selected]}" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val measure = sample ?: return@TextButton
                    sampling = true
                    coroutine.launch {
                        try {
                            val result = measure()
                            empty = result == null
                            if (result != null) update(result)
                        } finally { sampling = false }
                    }
                }, enabled = !sampling && sample != null) { Text("По этой странице") }
                TextButton({ empty = false; update(PdfCrop()) }, enabled = !sampling) { Text("Сбросить") }
            }
            if (empty) Text("Страница пустая. Укажите поля вручную.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(cancel) { Text("Отмена") }
                Button(save, enabled = !sampling) { Text("Сохранить") }
            }
        }
    }
}
