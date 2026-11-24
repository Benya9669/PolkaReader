package ru.polka.reader.data

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.util.Log
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.polka.reader.MainActivity
import ru.polka.reader.reader.*

/** run_live_data.py --range, against range_fixture.py on a real Polka server with synthetic books. */
@RunWith(AndroidJUnit4::class)
class LiveRangeTest {
    @Test fun renderBeforeReceivingWholeFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "polka-live-qa")
        assumeTrue(input.isFile)
        val password = try { input.readText().trim() } finally { check(input.delete()) }
        val data = PolkaData.get(context)
        val user = data.login("http://127.0.0.1:18763", "readerone", password)
        try {
            for (format in listOf("pdf", "epub")) {
                val book = data.catalog(user.scope, "Проверка диапазонов ${format.uppercase()}", null, 1).items.single()
                val file = book.editions.flatMap { it.files }.single { it.format == format }
                val parent = currentCoroutineContext()[Job]
                assertThrows(RangeUnavailable::class.java) {
                    ReaderSource.Remote.open(context, user.scope, file, parent) { _, _ -> throw RangeUnavailable() }
                }
                assertTrue("Полная загрузка должна остаться возможной", currentCoroutineContext().isActive)
                data.withReadingSource(user.scope, file.id, book.id, { _, _ -> error("Полная загрузка") }) { _, source, _ ->
                    assertTrue(source is ReaderSource.Remote)
                    if (format == "pdf") {
                        repeat(2) {
                            PdfRenderer(source.openPdf()).use { pdf ->
                                assertEquals(5, pdf.pageCount)
                                pdf.openPage(0).use { page ->
                                    val bitmap = Bitmap.createBitmap(180, 220, Bitmap.Config.ARGB_8888)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    assertNotEquals(bitmap.getPixel(8, 40), bitmap.getPixel(8, 120))
                                    bitmap.recycle()
                                }
                            }
                        }
                    } else {
                        val ready = CountDownLatch(1)
                        val outcome = AtomicReference("")
                        ActivityScenario.launch(MainActivity::class.java).use { activity ->
                            activity.onActivity { screen -> screen.setContent {
                                EpubPane(source, null, BookSettings(), emptyList(), {}, { place, _ ->
                                    outcome.set(place.locator); ready.countDown()
                                }, {}, {}, {}, { error -> outcome.set(error); ready.countDown() },
                                    inspection = androidx.compose.runtime.remember { ReaderInspection() },
                                    onRemainder = {}, onIllustration = {})
                            } }
                            assertTrue("EPUB did not render", ready.await(45, TimeUnit.SECONDS))
                            assertTrue(outcome.get(), outcome.get().startsWith("epubcfi("))
                        }
                    }
                    val received = (source as ReaderSource.Remote).receivedBytes
                    Log.i("PolkaRangeTest", "$format: $received / ${file.size} bytes at first render")
                    assertTrue("Первая страница потребовала $received из ${file.size} байт", received in 1 until file.size / 2)
                }
            }
        } finally { data.logout(user.scope) }
    }
}
