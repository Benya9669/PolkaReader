package ru.polka.reader.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.polka.reader.data.ReaderSource
import kotlin.math.roundToInt

internal data class FoundText(val locator: String, val label: String, val excerpt: String)
internal data class SearchReport(val hits: List<FoundText> = emptyList(), val limited: Boolean = false, val message: String = "")
internal data class PositionPreview(val locator: String, val label: String, val excerpt: String = "",
                                    val image: Bitmap? = null, val error: Boolean = false)

internal class ReaderInspection {
    var request by mutableIntStateOf(0)
        private set
    var search by mutableStateOf<SearchReport?>(null)
    var preview by mutableStateOf<PositionPreview?>(null)
    fun reset(): Int {
        ++request
        search = null
        preview = null
        return request
    }
    fun receive(event: JSONObject) {
        if (event.optInt("id", -1) != request) return
        val message = event.optString("error").take(240)
        if (event.optString("type") == "positionPreview") {
            preview = PositionPreview(event.optString("locator"), message.ifBlank { event.optString("label") },
                event.optString("excerpt").take(240), error = message.isNotBlank())
        } else {
            val items = event.optJSONArray("items") ?: return
            search = SearchReport((0 until minOf(100, items.length())).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val locator = item.optString("locator")
                if (!locator.startsWith("epubcfi(") || locator.length > 4000) return@mapNotNull null
                FoundText(locator, item.optString("label").take(500), item.optString("excerpt").take(400))
            }, event.optBoolean("limited"), message)
        }
    }
}

internal fun quantityHint(amount: Int, minutes: Boolean): String {
    if (amount <= 0) return if (minutes) "До конца главы меньше минуты" else "Последняя страница главы"
    if (minutes) return "До конца главы ≈ $amount мин"
    val ending = when {
        amount % 100 in 11..14 -> "страниц"
        amount % 10 == 1 -> "страница"
        amount % 10 in 2..4 -> "страницы"
        else -> "страниц"
    }
    return "До конца главы — $amount $ending"
}

@Composable
private fun CloseInspection(state: ReaderInspection, control: ((ReaderAction) -> Unit)?) {
    val current by rememberUpdatedState(control)
    DisposableEffect(state) {
        onDispose {
            state.reset()
            current?.invoke(ReaderAction("cancelInspection"))
        }
    }
}

@Composable
internal fun ReaderSearchDialog(source: ReaderSource, pdf: Boolean, state: ReaderInspection,
                                control: ((ReaderAction) -> Unit)?, close: () -> Unit, open: (String) -> Unit) {
    CloseInspection(state, if (pdf) null else control)
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val supported = !pdf || supportsPdfSearch()
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (query.isBlank() || !supported) return
        keyboard?.hide()
        submitted = query.trim()
        attempt++
    }
    LaunchedEffect(attempt, source, control != null) {
        if (submitted.isBlank() || control == null) return@LaunchedEffect
        val id = state.reset()
        if (pdf) {
            val result = try { searchPdf(source, submitted) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { SearchReport(message = "Не удалось прочитать текст PDF. Повторите поиск.") }
            if (state.request == id) state.search = result
        } else control(ReaderAction("search", requestId = id, query = submitted))
    }
    AlertDialog(onDismissRequest = close, title = { Text("Поиск в книге") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!supported) Text("Для поиска по тексту PDF нужен Android 15 или Android 12–14 с обновлённым модулем PDF.")
            else {
                OutlinedTextField(query, { value ->
                    query = value.take(160)
                    if (submitted.isNotEmpty()) {
                        submitted = ""; attempt++; state.reset()
                        if (!pdf) control?.invoke(ReaderAction("cancelInspection"))
                    }
                }, Modifier.fillMaxWidth(), label = { Text("Слово или фраза") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }))
                if (submitted.isNotEmpty()) {
                    val result = state.search
                    if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        if (result.message.isNotBlank()) Text(result.message)
                        else if (result.hits.isEmpty()) Text("Совпадений нет")
                        if (result.limited) Text("Показаны первые 100 совпадений. Уточните запрос.")
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(result.hits) { hit ->
                                Column(Modifier.fillMaxWidth().clickable { open(hit.locator) }.padding(vertical = 8.dp)) {
                                    Text(hit.label, fontWeight = FontWeight.Medium)
                                    Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = {
        if (supported) TextButton(::submit, enabled = query.isNotBlank()) { Text("Найти") }
    }, dismissButton = { TextButton(close) { Text("Закрыть") } })
}

@Composable
internal fun PositionPreviewDialog(source: ReaderSource, pdf: Boolean, count: Int, crop: PdfCrop, place: ReaderPlace,
                                   state: ReaderInspection, control: ((ReaderAction) -> Unit)?,
                                   close: () -> Unit, open: (String) -> Unit) {
    CloseInspection(state, if (pdf) null else control)
    var fraction by rememberSaveable { mutableFloatStateOf(if (pdf && count > 1)
        ((place.locator.toIntOrNull() ?: 1) - 1f) / (count - 1) else place.percentage.toFloat()) }
    val page = 1 + (fraction * (count - 1).coerceAtLeast(0)).roundToInt()
    LaunchedEffect(if (pdf) page.toFloat() else fraction, source, control != null) {
        val id = state.reset()
        delay(100)
        if (pdf) {
            val result = try { pdfThumbnail(source, page, crop) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { PositionPreview("", "Не удалось открыть миниатюру страницы.", error = true) }
            if (id == state.request) state.preview = result
        } else control?.invoke(ReaderAction("previewPosition", percentage = fraction.toDouble(), requestId = id))
    }
    val target = state.preview
    AlertDialog(onDismissRequest = close, title = { Text("Просмотр страниц") }, text = {
        Column {
            Text(if (pdf) "Страница $page из $count" else "Место в книге · ${(fraction * 100).roundToInt()}%")
            Slider(value = fraction, onValueChange = { value ->
                if (value != fraction && (!pdf || page != 1 + (value * (count - 1).coerceAtLeast(0)).roundToInt())) state.reset()
                fraction = value
            }, enabled = !pdf || count > 1, modifier = Modifier.semantics { contentDescription = "Место для просмотра" })
            if (target == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (target.image != null) {
                Image(target.image.asImageBitmap(), target.label, Modifier.fillMaxWidth().heightIn(max = 200.dp))
            } else {
                Text(target.label, style = MaterialTheme.typography.titleSmall)
                if (target.excerpt.isNotEmpty()) Text(target.excerpt, Modifier.padding(top = 8.dp), maxLines = 5)
            }
        }
    }, confirmButton = {
        TextButton({ target?.let { open(it.locator) } }, enabled = target?.let { !it.error && it.locator.isNotBlank() } == true) {
            Text("Перейти")
        }
    }, dismissButton = { TextButton(close) { Text("Отмена") } })
}
