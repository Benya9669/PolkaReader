package ru.polka.reader.reader

import kotlin.math.max

/** Margins as percentages of an unrotated, rendered PDF page, in display order. */
data class PdfCrop(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0) {
    init { require(listOf(left, top, right, bottom).all { it in 0..MAX }) }

    internal fun encode() = listOf(left, top, right, bottom).joinToString(",")

    companion object {
        internal const val MAX = 35

        internal fun decode(encoded: String?): PdfCrop {
            val values = encoded?.split(',')?.map { it.toIntOrNull() } ?: return PdfCrop()
            if (values.size != 4 || values.any { it == null || it !in 0..MAX }) return PdfCrop()
            return PdfCrop(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
        }

        /** Sample a small opaque thumbnail. Ignore isolated scanner specks and keep a safety margin. */
        internal fun detect(pixels: IntArray, width: Int, height: Int): PdfCrop? {
            if (width < 3 || height < 3 || pixels.size != width * height) return null
            val rows = IntArray(height)
            val columns = IntArray(width)
            pixels.forEachIndexed { index, color ->
                val r = color ushr 16 and 255
                val g = color ushr 8 and 255
                val b = color and 255
                if (minOf(r, g, b) < 236) {
                    columns[index % width]++
                    rows[index / width]++
                }
            }
            val rowMin = max(2, width / 160)
            val columnMin = max(2, height / 160)
            val top = rows.indexOfFirst { it >= rowMin }
            val left = columns.indexOfFirst { it >= columnMin }
            if (top < 0 || left < 0) return null
            val bottom = height - 1 - rows.indexOfLast { it >= rowMin }
            val right = width - 1 - columns.indexOfLast { it >= columnMin }
            fun margin(blank: Int, size: Int) = (100 * blank / size - 2).coerceIn(0, MAX)
            return PdfCrop(margin(left, width), margin(top, height), margin(right, width), margin(bottom, height))
        }
    }
}

/** Zero means toggle the panels. A physical side choice changes taps only, never writing direction. */
internal fun pageTapDirection(fraction: Float, zone: Int, side: String, rtl: Boolean = false): Int {
    val edge = zone.coerceIn(12, 36) / 100f
    val physical = when {
        fraction < edge -> -1
        fraction > 1 - edge -> 1
        else -> return 0
    }
    return if (side == "left" || (side != "right" && rtl)) -physical else physical
}
