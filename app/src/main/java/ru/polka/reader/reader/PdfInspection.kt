package ru.polka.reader.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.polka.reader.data.ReaderSource
import kotlin.math.roundToInt

internal fun supportsPdfSearch(): Boolean = Build.VERSION.SDK_INT >= 35 ||
    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13)

private suspend fun scanPages(count: Int, query: String, content: (Int) -> String): SearchReport {
    val hits = mutableListOf<FoundText>()
    var hasText = false
    val gaps = Regex("\\s+")
    val phrase = query.trim().replace(gaps, " ")
    if (phrase.isEmpty()) return SearchReport()
    for (page in 0 until count) {
        currentCoroutineContext().ensureActive()
        val text = content(page).replace(gaps, " ").trim()
        if (text.isBlank()) continue
        hasText = true
        var offset = text.indexOf(phrase, ignoreCase = true)
        while (offset >= 0) {
            if (hits.size == 100) return SearchReport(hits, limited = true)
            val start = (offset - 52).coerceAtLeast(0)
            val end = (offset + phrase.length + 64).coerceAtMost(text.length)
            val excerpt = (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
            hits.add(FoundText((page + 1).toString(), "Страница ${page + 1}", excerpt))
            offset = text.indexOf(phrase, startIndex = offset + phrase.length, ignoreCase = true)
        }
    }
    return SearchReport(hits, message = if (hasText) "" else "В этом PDF нет текстового слоя. Поиск по сканам недоступен.")
}

internal suspend fun searchPdf(source: ReaderSource, query: String): SearchReport = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT >= 35) {
        val descriptor = source.openPdf()
        val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
        renderer.use { document ->
            scanPages(document.pageCount, query) { index ->
                document.openPage(index).use { it.textContents.joinToString(" ") { part -> part.text } }
            }
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13) {
        extensionSearch(source, query)
    } else SearchReport(message = "На этом устройстве поиск по тексту PDF недоступен.")
}

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
private suspend fun extensionSearch(source: ReaderSource, query: String): SearchReport {
    val descriptor = source.openPdf()
    val renderer = try { PdfRendererPreV(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    return renderer.use { document ->
        scanPages(document.pageCount, query) { index ->
            document.openPage(index).use { it.textContents.joinToString(" ") { part -> part.text } }
        }
    }
}

internal suspend fun pdfThumbnail(source: ReaderSource, number: Int, crop: PdfCrop): PositionPreview = withContext(Dispatchers.IO) {
    val descriptor = source.openPdf()
    val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    renderer.use { document ->
        val target = number.coerceIn(1, document.pageCount)
        document.openPage(target - 1).use { page ->
            val scale = 480.0 / maxOf(page.width, page.height)
            val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).roundToInt()),
                maxOf(1, (page.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val cropped = Bitmap.createBitmap(bitmap, bitmap.width * crop.left / 100, bitmap.height * crop.top / 100,
                maxOf(1, bitmap.width - bitmap.width * crop.left / 100 - bitmap.width * crop.right / 100),
                maxOf(1, bitmap.height - bitmap.height * crop.top / 100 - bitmap.height * crop.bottom / 100))
            if (cropped !== bitmap) bitmap.recycle()
            PositionPreview(target.toString(), "Миниатюра страницы $target", image = cropped)
        }
    }
}
