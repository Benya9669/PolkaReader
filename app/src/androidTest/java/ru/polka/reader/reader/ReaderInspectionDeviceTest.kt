package ru.polka.reader.reader

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.polka.reader.MainActivity
import ru.polka.reader.data.ReaderSource
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ReaderInspectionDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun pdf(withText: Boolean): File {
        val file = File.createTempFile("inspection-", ".pdf", ui.activity.cacheDir)
        val document = PdfDocument()
        try {
            for (number in 1..3) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, number).create())
                val paint = Paint().apply { color = Color.BLUE; textSize = 18f }
                page.canvas.drawRect(10f, 10f, 40f, 40f, paint)
                if (withText) page.canvas.drawText(if (number == 3) "The NORTH cape light" else "Page $number", 16f, 100f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }

    private fun awaitText(text: String) = ui.waitUntil(15_000) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun realPdfSearchAndScrubberSaveOnlyAfterConfirmationAndReturnToOrigin() {
        assumeTrue(supportsPdfSearch())
        val file = pdf(true)
        val source = ReaderSource.Local(file)
        val saved = CopyOnWriteArrayList<String>()
        try {
            ui.runOnUiThread {
                ui.activity.setContent {
                    MaterialTheme {
                        BookReader(source, "pdf", "Проверка поиска", null, BookSettings(), {}, { saved.add(it.locator) },
                            {}, {}, {}, {}, onProfile = {})
                    }
                }
            }
            awaitText("Стр. 1 из 3")
            ui.onNodeWithContentDescription("Поиск в книге").performClick()
            ui.onNode(hasSetTextAction()).performTextInput("north cape")
            ui.onNodeWithText("Найти").performClick()
            awaitText("The NORTH cape light")
            assertTrue(saved.isEmpty())
            ui.onNodeWithText("The NORTH cape light").performClick()
            awaitText("Стр. 3 из 3")
            assertEquals(listOf("3"), saved.toList())
            ui.onNodeWithText("Вернуться к месту чтения").performClick()
            awaitText("Стр. 1 из 3")
            for (confirm in listOf(false, true)) {
                ui.onNodeWithContentDescription("Просмотреть страницы").performClick()
                ui.onNodeWithContentDescription("Место для просмотра").performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
                ui.waitUntil(15_000) {
                    ui.onAllNodesWithContentDescription("Миниатюра страницы 2").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(listOf("3", "1"), saved.toList())
                ui.onNodeWithText(if (confirm) "Перейти" else "Отмена").performClick()
                awaitText(if (confirm) "Стр. 2 из 3" else "Стр. 1 из 3")
            }
            assertEquals(listOf("3", "1", "2"), saved.toList())
        } finally { file.delete() }
    }

    @Test fun imageOnlyPdfHasThumbnailAndExplainsItsMissingTextLayer() = runBlocking {
        val file = pdf(false)
        try {
            val source = ReaderSource.Local(file)
            val image = pdfThumbnail(source, 2, PdfCrop()).image!!
            assertEquals(480, image.height)
            assertTrue(Color.blue(image.getPixel(25, 25)) > Color.red(image.getPixel(25, 25)))
            if (supportsPdfSearch()) {
                val report = searchPdf(source, "карта")
                assertTrue(report.hits.isEmpty())
                assertTrue(report.message.contains("нет текстового слоя"))
            }
        } finally { file.delete() }
    }
}
