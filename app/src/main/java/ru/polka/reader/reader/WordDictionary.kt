package ru.polka.reader.reader

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private fun dictionary(context: Context) = AtomicFile(File(context.filesDir, "reader-dictionary.tsv"))
private val dictionaryLock = Any()

internal suspend fun importWords(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    synchronized(dictionaryLock) {
        val file = dictionary(context)
        val candidate = File.createTempFile("dictionary-", ".tsv", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть словарь")
            input.use { stream -> candidate.outputStream().use { target ->
                var size = 0
                val buffer = ByteArray(32768)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 16 * 1024 * 1024) { "Максимальный размер словаря — 16 МиБ" }
                    target.write(buffer, 0, count)
                }
            } }
            require(candidate.bufferedReader().useLines { lines -> lines.any {
                it.substringBefore('\t').isNotBlank() && it.substringAfter('\t', "").isNotBlank()
            } }) { "Нужен словарь в формате TSV" }
            val target = file.startWrite()
            try {
                candidate.inputStream().use { it.copyTo(target) }
                file.finishWrite(target)
            } catch (e: Exception) { file.failWrite(target); throw e }
        } finally { candidate.delete() }
    }
}

internal suspend fun lookupWord(context: Context, word: String): String = withContext(Dispatchers.IO) {
            synchronized(dictionaryLock) {
                try {
                    dictionary(context).openRead().bufferedReader().useLines { lines ->
                        lines.filter { it.substringBefore('\t').trimStart('\uFEFF').trim().equals(word,true) && '\t' in it }
                            .take(5).map { it.substringAfter('\t').take(4000) }.joinToString("\n\n").ifBlank { "Слово отсутствует в словаре." }
                    }
                } catch (_: java.io.FileNotFoundException) { "Выберите офлайн-словарь TSV в UTF-8. В каждой строке — слово, табуляция и толкование или перевод." }
                  catch (_: IOException) { "Не удалось прочитать словарь" }
            }
        }

@Composable internal fun WordDictionary(selection: String, closeSelection: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    if (selection.trim().length !in 1..120) return
    TextButton({ open = true }) { Text("Словарь") }
    if (!open) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf("") }
    val word = selection.trim().trim { !it.isLetterOrDigit() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try { importWords(context, uri); revision++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { result = e.localizedMessage ?: "Не удалось прочитать словарь" }
        }
    }
    LaunchedEffect(word,revision) {
        result = lookupWord(context, word)
    }
    AlertDialog(onDismissRequest = { open = false; closeSelection() }, title = { Text(word) }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text(result)
            TextButton({
                val lookup = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, word)
                    .putExtra(android.content.Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                try { context.startActivity(android.content.Intent.createChooser(lookup, "Словарь или переводчик")) }
                catch (_: android.content.ActivityNotFoundException) { result = "Выберите файл офлайн-словаря TSV." }
            }) { Text("Открыть в словаре или переводчике") }
        }
    }, confirmButton = { TextButton({ open = false; closeSelection() }) { Text("К тексту") } },
        dismissButton = { TextButton({ pick.launch(arrayOf("*/*")) }) { Text("Выбрать словарь") } })
}
