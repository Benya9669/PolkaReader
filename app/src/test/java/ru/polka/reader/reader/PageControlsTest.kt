package ru.polka.reader.reader

import org.junit.Assert.*
import org.junit.Test

class PageControlsTest {
    @Test fun tapZonesKeepTheCentreAndRespectReadingDirection() {
        assertEquals(0, pageTapDirection(.2f, 12, "auto"))
        assertEquals(-1, pageTapDirection(.2f, 30, "auto"))
        assertEquals(1, pageTapDirection(.2f, 30, "auto", rtl = true))
        assertEquals(1, pageTapDirection(.02f, 36, "left"))
        assertEquals(-1, pageTapDirection(.95f, 36, "left"))
        assertEquals(1, pageTapDirection(.95f, 36, "right", rtl = true))
        assertEquals(0, pageTapDirection(.5f, 100, "left"))
    }

    @Test fun detectAsymmetricMarginsAndIgnoreIsolatedScannerNoise() {
        val pixels = IntArray(100 * 100) { -1 }
        for (y in 22 until 82) for (x in 17 until 87) pixels[y * 100 + x] = 0xff222222.toInt()
        pixels[0] = 0xff000000.toInt()
        assertEquals(PdfCrop(15, 20, 11, 16), PdfCrop.detect(pixels, 100, 100))
        assertNull(PdfCrop.detect(IntArray(100) { -1 }, 10, 10))
        assertEquals(PdfCrop(), PdfCrop.detect(IntArray(100) { 0xff111111.toInt() }, 10, 10))
    }

    @Test fun savedMarginsRoundTripAndInvalidPreferencesFallBackToWholePage() {
        val crop = PdfCrop(4, 8, 13, 2)
        assertEquals(crop, PdfCrop.decode(crop.encode()))
        for (value in listOf(null, "", "4,8,16", "5,0,36,2", "-1,0,0,0", "x,0,5,3"))
            assertEquals(PdfCrop(), PdfCrop.decode(value))
    }
}
