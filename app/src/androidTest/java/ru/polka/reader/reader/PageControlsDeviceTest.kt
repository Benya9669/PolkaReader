package ru.polka.reader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.polka.reader.Appearance
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PageControlsDeviceTest {
    @Test fun croppedPageUsesOriginalCoordinatesAndResetRestoresFullSheet() {
        val painter = PdfPagePainter(1f)
        val paper = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        paper.eraseColor(Color.WHITE)
        Canvas(paper).drawRect(20f, 20f, 80f, 80f, Paint().apply { color = Color.RED })
        val pages = PdfPages(1, 1, listOf(PdfPage(paper, "1")))
        val surface = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        fun draw() = painter.draw(Canvas(surface), 100, 100, pages, null, false, "none", 0, 0f, 1f, 0f, 0f)
        draw()
        assertEquals(Color.WHITE, surface.getPixel(10, 15))
        painter.crop = PdfCrop(20, 20, 20, 20)
        draw()
        assertEquals(Color.RED, surface.getPixel(10, 15))
        painter.crop = PdfCrop()
        draw()
        assertEquals(Color.WHITE, surface.getPixel(10, 15))
    }

    @Test fun cropRestoresForSameFileAndAccountOnly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val saved = PdfCrop(10, 6, 4, 3)
        val appearance = Appearance(context)
        try {
            appearance.saveCrop(account, 8, saved)
            assertEquals(saved, Appearance(context).fileCrop(account, 8).first())
            assertEquals(PdfCrop(), appearance.fileCrop(account, 9).first())
            assertEquals(PdfCrop(), appearance.fileCrop(other, 8).first())
        } finally {
            appearance.saveCrop(account, 8, PdfCrop())
        }
    }

    @Test fun hardwareVolumeTurnsOncePerPressAndPassesThroughWhenDisabled() {
        val keys = ReaderVolumeKeys()
        val calls = mutableListOf<Boolean>()
        val lower = KeyEvent.KEYCODE_VOLUME_DOWN
        val higher = KeyEvent.KEYCODE_VOLUME_UP
        fun down(key: Int, repeat: Int = 0) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, key, repeat)
        fun up(key: Int) = KeyEvent(KeyEvent.ACTION_UP, key)
        assertFalse(keys.handle(down(lower)))
        assertFalse(keys.handle(up(lower)))
        keys.turn = { calls.add(it); true }
        assertTrue(keys.handle(down(lower)))
        assertTrue(keys.handle(down(lower, 1)))
        assertTrue(keys.handle(up(lower)))
        assertTrue(keys.handle(down(higher)))
        keys.turn = null
        assertTrue(keys.handle(up(higher)))
        assertFalse(keys.handle(down(higher)))
        assertEquals(listOf(true, false), calls)
    }
}
