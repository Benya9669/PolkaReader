package ru.polka.reader.reader

import android.graphics.*
import kotlin.math.*

/** All bitmaps passed to the painter are already decoded. Geometry follows the current drag fraction. */
internal class PdfPagePainter(private val density: Float) {
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val bounds = RectF()
    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val shape = Path()
    private val desk = Color.rgb(58, 59, 57)
    private val shadow = LinearGradient(0f, 0f, 1f, 0f, 0x46000000, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val back = LinearGradient(0f, 0f, 1f, 0f,
        intArrayOf(0xda918573.toInt(), 0xeae0d9cb.toInt(), 0xc9f7f2e9.toInt(), 0xbba99e90.toInt()),
        floatArrayOf(0f, .22f, .68f, 1f), Shader.TileMode.CLAMP)
    var maxPanX = 0f
        private set
    var maxPanY = 0f
        private set
    var minPanX = 0f
        private set
    var minPanY = 0f
        private set

    private fun position(page: PdfPage, slot: Int, w: Float, h: Float, spread: Boolean): RectF {
        val seam = if (spread) 1.5f * density else 0f
        val available = if (spread) w * .5f - seam else w
        val bitmap = page.bitmap
        val fit = min(available.coerceAtLeast(1f) / bitmap.width, h / bitmap.height)
        val pageW = fit * bitmap.width
        val pageH = fit * bitmap.height
        val left = when {
            !spread -> (w - pageW) / 2
            slot == 0 -> w / 2 - seam - pageW
            else -> w / 2 + seam
        }
        rect.set(left, (h - pageH) / 2, left + pageW, (h + pageH) / 2)
        return rect
    }

    private fun leaf(canvas: Canvas, pages: PdfPages, slot: Int, w: Float, h: Float, spread: Boolean) {
        val page = pages.pages.getOrNull(slot) ?: return
        val area = position(page, slot, w, h, spread)
        canvas.drawBitmap(page.bitmap, null, area, paper)
        if (spread) {
            val inner = if (slot == 0) area.right else area.left
            val side = if (slot == 0) -1 else 1
            val end = inner + side * min(11 * density, area.width() * .07f)
            gradient(canvas, shadow, inner, end, area.top, area.bottom, 110)
        }
    }

    private fun group(canvas: Canvas, p: PdfPages, w: Float, h: Float, spread: Boolean) {
        for (i in p.pages.indices) leaf(canvas, p, i, w, h, spread)
    }

    private fun gradient(canvas: Canvas, shader: Shader, from: Float, to: Float, top: Float, bottom: Float, alpha: Int = 255) {
        if (abs(to - from) < .3f) return
        matrix.setScale(to - from, 1f)
        matrix.postTranslate(from, 0f)
        shader.setLocalMatrix(matrix)
        shade.shader = shader
        shade.alpha = alpha
        canvas.drawRect(min(from, to), top, max(from, to), bottom, shade)
        shade.shader = null
        shade.alpha = 255
    }

    fun draw(canvas: Canvas, width: Int, height: Int, current: PdfPages?, next: PdfPages?, spread: Boolean,
             mode: String, dir: Int, fraction: Float, zoom: Float, panX: Float, panY: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(desk)
        if (current == null || w < 1 || h < 1) return
        bounds.setEmpty()
        for (slot in current.pages.indices) bounds.union(position(current.pages[slot], slot, w, h, spread))
        // A final unpaired page sits on the left, so horizontal pan limits need not be symmetric.
        minPanX = min(0f, w / 2 - (bounds.right - w / 2) * zoom)
        maxPanX = max(0f, -w / 2 - (bounds.left - w / 2) * zoom)
        minPanY = min(0f, h / 2 - (bounds.bottom - h / 2) * zoom)
        maxPanY = max(0f, -h / 2 - (bounds.top - h / 2) * zoom)
        canvas.save()
        canvas.translate(panX.coerceIn(minPanX, maxPanX), panY.coerceIn(minPanY, maxPanY))
        canvas.scale(zoom, zoom, w / 2, h / 2)
        when {
            next == null || fraction <= 0f || dir == 0 || mode == "none" -> group(canvas, current, w, h, spread)
            fraction >= 1f -> group(canvas, next, w, h, spread)
            mode == "curl" && spread -> spreadCurl(canvas, current, next, w, h, dir, fraction)
            mode == "curl" -> singleCurl(canvas, current, next, w, h, dir, fraction)
            else -> {
                val offset = -dir * w * fraction
                canvas.translate(offset, 0f)
                group(canvas, current, w, h, spread)
                canvas.translate(dir * w, 0f)
                group(canvas, next, w, h, spread)
            }
        }
        canvas.restore()
    }

    /** A sheet bends about the binding. Once a strip crosses edge-on its reverse is the arriving page. */
    private fun spreadCurl(c: Canvas, old: PdfPages, next: PdfPages, w: Float, h: Float, dir: Int, p: Float) {
        val moving = if (dir > 0) 1 else 0
        val landing = 1 - moving
        leaf(c, next, moving, w, h, true)
        leaf(c, old, landing, w, h, true)
        val middle = w / 2
        val turn = Math.PI * p
        val bend = sin(turn)
        val slices = 14
        var x = middle
        var z = 0f
        for (i in 0 until slices) {
            val u0 = i.toFloat() / slices
            val u1 = (i + 1f) / slices
            val angle = turn + bend * (u0 + u1 - 1) * .48
            val dx = (dir * middle * cos(angle) / slices).toFloat()
            val endX = x + dx
            val endZ = z + (middle * sin(angle) / slices).toFloat()
            if (abs(dx) >= .15f) {
                val front = dx * dir >= 0
                val side = if (front) dir else -dir
                val a = middle + side * middle * u0
                val b = middle + side * middle * u1
                quad(src, a, 0f, b, 0f, h, h)
                val topA = -min(z * .052f, h * .04f)
                val topB = -min(endZ * .052f, h * .04f)
                quad(dst, x, topA, endX, topB, h - topA, h - topB)
                matrix.setPolyToPoly(src, 0, dst, 0, 4)
                c.save()
                c.concat(matrix)
                c.clipRect(min(a, b), 0f, max(a, b) + .4f, h)
                c.drawColor(desk)
                leaf(c, if (front) old else next, if (front) moving else landing, w, h, true)
                val alpha = (53 * (1 - abs(cos(angle)))).toInt()
                c.drawColor(Color.argb(alpha, 88, 66, 43))
                c.restore()
            }
            x = endX
            z = endZ
        }
        gradient(c, shadow, x, x + dir * (20 * density * bend).toFloat(), 0f, h)
    }

    private fun quad(v: FloatArray, ax: Float, ay: Float, bx: Float, by: Float, bottomA: Float, bottomB: Float) {
        v[0] = ax; v[1] = ay; v[2] = bx; v[3] = by
        v[4] = bx; v[5] = bottomB; v[6] = ax; v[7] = bottomA
    }

    private fun singleCurl(c: Canvas, old: PdfPages, next: PdfPages, w: Float, h: Float, dir: Int, p: Float) {
        group(c, next, w, h, false)
        val depth = sin(Math.PI * p).toFloat()
        val edge = if (dir > 0) w * (1 - p) else w * p
        val ribbon = min(w * .13f, 62 * density) * depth
        val tilt = dir * min(w * .035f, h * .033f) * depth
        val spine = edge - dir * ribbon * .5f
        val tip = edge + dir * ribbon * .5f
        val fixed = if (dir > 0) 0f else w
        shape.reset()
        shape.moveTo(fixed, 0f)
        shape.lineTo(spine + tilt, 0f)
        shape.quadTo(spine - tilt * .65f, h * .55f, spine - tilt, h)
        shape.lineTo(fixed, h)
        shape.close()
        c.save()
        c.clipPath(shape)
        c.drawColor(desk)
        group(c, old, w, h, false)
        c.restore()

        shape.reset()
        shape.moveTo(spine + tilt, 0f)
        shape.quadTo(spine - tilt * .65f, h * .55f, spine - tilt, h)
        shape.lineTo(tip - tilt, h)
        shape.quadTo(tip - tilt * .3f, h * .48f, tip + tilt, 0f)
        shape.close()
        c.save()
        c.clipPath(shape)
        c.drawColor(Color.rgb(230, 224, 213))
        c.save()
        c.scale(-1f, 1f, spine, h / 2)
        group(c, old, w, h, false)
        c.restore()
        gradient(c, back, spine - dir * abs(tilt), tip + dir * abs(tilt), 0f, h)
        c.restore()
        gradient(c, shadow, tip + dir * abs(tilt), tip + dir * (abs(tilt) + 17 * density * depth), 0f, h)
    }
}
