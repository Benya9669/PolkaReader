package ru.polka.reader.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.polka.reader.data.ReaderSource
import kotlin.math.*

internal data class PdfPage(val bitmap: Bitmap, val description: String)
internal data class PdfPages(val first: Int, val total: Int, val pages: List<PdfPage>)
internal fun firstPage(number: Int, spread: Boolean, cover: Boolean) = if (!spread || number == 1) number else number - (number - if (cover) 0 else 1) % 2

private fun pageDescription(page: PdfRenderer.Page, number: Int, count: Int): String = buildString {
    append("Страница $number из $count.")
    if (Build.VERSION.SDK_INT >= 35) {
        try {
            val limit = 16_000
            for (part in page.textContents) {
                if (part.text.isBlank()) continue
                append('\n')
                val room = limit - length
                if (part.text.length >= room) {
                    append(part.text, 0, room.coerceAtLeast(0))
                    append('…')
                    break
                }
                append(part.text)
            }
        } catch (_: RuntimeException) {
            Log.w("PolkaPDF", "Could not extract the PDF text layer for accessibility")
        }
    }
}

private fun renderPages(file: ReaderSource, requested: Int, width: Int, spread: Boolean, cover: Boolean): PdfPages {
    val fd = file.openPdf()
    val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
    return renderer.use { document ->
        val count = document.pageCount
        require(count > 0)
        val first = firstPage(requested.coerceIn(1, count), spread, cover)
        val slots = if (spread && count > 1 && !(cover && first == 1)) 2 else 1
        // At most three nearby spreads are retained. Bound decoded memory even on small devices.
        val pixelBudget = min(3_200_000.0, Runtime.getRuntime().maxMemory() / 64.0) / slots
        val pages = (first..min(first + slots - 1, count)).map { number ->
            document.openPage(number - 1).use { page ->
                val factor = minOf(width * 1.8 / (slots * page.width),
                    sqrt(pixelBudget / (page.width.toDouble() * page.height)), 4096.0 / max(page.width, page.height))
                val bitmap = Bitmap.createBitmap(max(1, (factor * page.width).roundToInt()),
                    max(1, (factor * page.height).roundToInt()), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                PdfPage(bitmap, pageDescription(page, number, count))
            }
        }
        PdfPages(first, count, pages)
    }
}

private class PdfCanvas(context: Context, private val file: ReaderSource, first: Int) : View(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decoder = Mutex()
    private var load: Job? = null
    private val nearby = mutableMapOf<Int, PdfPages>()
    private val unavailable = mutableSetOf<Int>()
    private val painter = PdfPagePainter(resources.displayMetrics.density)
    private var current: PdfPages? = null
    private var number = max(1, first)
    private var pendingJump: Int? = null
    private var persistJump = false
    private var columns = false
    private var cover = false
    private var segment = 0
    private var previousColumnEnd = false
    private var spread = false
    private var mode = "slide"
    private var tapSide = "auto"
    private var tapZone = 23
    private val step get() = if (spread) 2 else 1

    private var direction = 0
    private var progress = 0f
    private var finishWhenReady = false
    private var animator: ValueAnimator? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    var onTap: () -> Unit = {}
    var onZoom: (Float) -> Unit = {}
    var onPlace: (ReaderPlace, Boolean) -> Unit = { _, _ -> }
    var onCount: (Int) -> Unit = {}
    var onError: (String) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multiple = false
    private var ignoreGesture = false
    private var tracker: VelocityTracker? = null
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val updated = (zoom * detector.scaleFactor).coerceIn(.65f, 3f)
            val ratio = updated / zoom
            val x = detector.focusX - width / 2f
            val y = detector.focusY - height / 2f
            panX = x + (panX - x) * ratio
            panY = y + (panY - y) * ratio
            zoom = updated
            invalidate()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) = onZoom(zoom)
    }).apply { isQuickScaleEnabled = false }

    init { isClickable = true; isFocusable = true }

    fun configure(newZoom: Float, turnMode: String, newSpread: Boolean, crop: PdfCrop, side: String, edge: Int, columnsMode: Boolean, separateCover: Boolean) {
        tapSide = side
        tapZone = edge
        if (painter.crop != crop) {
            resetTurn()
            panX = 0f
            panY = 0f
            painter.crop = crop
            invalidate()
        }
        if (mode != turnMode) { resetTurn(); mode = turnMode }
        if (!scale.isInProgress && zoom != newZoom) { zoom = newZoom; invalidate() }
        if (spread != newSpread || columns != columnsMode || cover != separateCover) {
            spread = newSpread; columns = columnsMode; cover = separateCover; segment = 0; reload()
        }
    }

    suspend fun suggestCrop(): PdfCrop? {
        val pages = current ?: return null
        val bitmap = pages.pages.getOrNull(number - pages.first)?.bitmap ?: return null
        return withContext(Dispatchers.Default) {
            val ratio = min(1.0, 320.0 / max(bitmap.width, bitmap.height))
            val width = max(1, (bitmap.width * ratio).roundToInt())
            val height = max(1, (bitmap.height * ratio).roundToInt())
            val sample = Bitmap.createScaledBitmap(bitmap, width, height, true)
            try {
                val pixels = IntArray(width * height)
                sample.getPixels(pixels, 0, width, 0, 0, width, height)
                PdfCrop.detect(pixels, width, height)
            } finally {
                if (sample !== bitmap) sample.recycle()
            }
        }
    }

    private fun reload() {
        load?.cancel(); load = null
        ignoreGesture = true
        resetTurn()
        nearby.clear(); unavailable.clear()
        current = null
        panX = 0f; panY = 0f
        pump()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ignoreGesture = true
        if (w != oldw) reload() else resetTurn()
    }

    private fun adjacent(dir: Int): Int? = current?.let { pages ->
        (if (spread && cover && pages.first == 1 && dir > 0) 2 else if (spread && cover && pages.first == 2 && dir < 0) 1 else pages.first + step * dir).takeIf { dir != 0 && it in 1..pages.total }
    }

    /** Serialize decoding. An explicit jump/gesture has priority over speculative neighbours. */
    private fun pump() {
        if (load != null || width <= 0 || !scope.isActive) return
        val wanted = firstPage(pendingJump ?: number, spread, cover)
        val target = listOfNotNull(wanted, adjacent(direction), adjacent(1), adjacent(-1))
            .firstOrNull { it !in nearby && it !in unavailable } ?: return
        val w = width
        val doublePage = spread
        val separateCover = cover
        load = scope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    // A native render already in progress may finish after a resize cancels its coroutine.
                    decoder.withLock { renderPages(file, target, w, doublePage, separateCover) }
                }
                nearby[pages.first] = pages
                if (target == firstPage(pendingJump ?: number, spread, cover)) {
                    show(pages, pendingJump ?: number, persistJump)
                } else if (finishWhenReady && adjacent(direction) == pages.first) {
                    settle(true)
                }
                invalidate()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                unavailable.add(target)
                if (current == null || target == firstPage(pendingJump ?: number, spread, cover) || adjacent(direction) == target) {
                    pendingJump = null; persistJump = false; resetTurn()
                    onError("Не удалось открыть страницу PDF. Проверьте локальную копию.")
                }
            } finally {
                if (isActive) { load = null; pump() }
            }
        }
    }

    private fun show(pages: PdfPages, exact: Int, persist: Boolean) {
        number = exact.coerceIn(pages.first, pages.first + pages.pages.lastIndex)
        if (current == null) lineUntil = android.os.SystemClock.uptimeMillis() + 2200
        current = pages
        segment = if (previousColumnEnd && columns) columnSteps() * 2 - 1 else 0
        previousColumnEnd = false
        pendingJump = null; persistJump = false
        nearby.keys.retainAll(setOf(pages.first, pages.first - step, pages.first + step))
        panX = 0f; panY = 0f
        val selected = pages.pages[number - pages.first]
        contentDescription = (listOf(selected) + pages.pages.filter { it !== selected })
            .joinToString("\n") { it.description } + " Нажмите в центре, чтобы показать или скрыть панели."
        onCount(pages.total)
        onPlace(ReaderPlace(number.toString(), number.toDouble() / pages.total), persist)
        invalidate()
    }

    fun navigate(action: ReaderAction) {
        if (action.type == "next" || action.type == "previous") {
            if (animator != null || finishWhenReady || pendingJump != null) return
            if (columns) {
                val dir = if (action.type == "next") 1 else -1
                val next = segment + dir
                if (next in 0 until columnSteps() * 2) { segment = next; invalidate(); return }
                if (adjacent(dir) == null) return
                previousColumnEnd = dir < 0
            }
            begin(if (action.type == "next") 1 else -1)
            if (direction != 0) settle(true)
            return
        }
        if (action.type != "jump") return
        val total = current?.total ?: return
        val exact = (action.locator?.toIntOrNull() ?: action.percentage?.let { ceil(it * total).toInt() } ?: number)
            .coerceIn(1, total)
        resetTurn()
        pendingJump = exact; persistJump = action.persist
        val first = firstPage(exact, spread, cover)
        unavailable.remove(first)
        nearby[first]?.let { show(it, exact, action.persist) }
        pump()
    }

    private fun begin(dir: Int) {
        val target = adjacent(dir)
        if (target == null) { direction = 0; progress = 0f; invalidate(); return }
        direction = dir
        if (unavailable.remove(target)) pump()
    }

    private fun resetTurn() {
        animator?.removeAllListeners(); animator?.cancel(); animator = null
        finishWhenReady = false; direction = 0; progress = 0f
        invalidate()
    }

    private fun settle(complete: Boolean) {
        val next = adjacent(direction)?.let(nearby::get)
        if (complete && next == null) { finishWhenReady = true; pump(); return }
        finishWhenReady = false
        fun finish() {
            resetTurn()
            if (complete && next != null) { show(next, next.first, true); pump() }
        }
        val target = if (complete) 1f else 0f
        if (columns || mode == "none" || !ValueAnimator.areAnimatorsEnabled() || progress == target) { finish(); return }
        animator?.removeAllListeners(); animator?.cancel()
        val animation = ValueAnimator.ofFloat(progress, target).apply {
            duration = (105 + abs(target - progress) * 145).toLong()
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
        }
        animator = animation
        animation.start()
    }

    override fun performClick(): Boolean { super.performClick(); onTap(); return true }

    var onLine: (String) -> Unit = {}
    var line = ""
    private var lineUntil = android.os.SystemClock.uptimeMillis() + 2500
    private var candidateLine = false
    private var secondX = 0f
    private var secondY = 0f
    private fun area(pages: PdfPages, slot: Int): android.graphics.RectF {
        if (columns) {
            val b = pages.pages[0].bitmap
            val croppedRatio = b.height * (100f - painter.crop.top - painter.crop.bottom) /
                (b.width * (100f - painter.crop.left - painter.crop.right))
            val h = width * 2 * croppedRatio
            val steps = columnSteps()
            val shift = (h - height).coerceAtLeast(0f) * (segment % steps) / max(1, steps - 1)
            return android.graphics.RectF(0f,-shift,width.toFloat(),h-shift)
        }
        return painter.displayedArea(pages.pages[slot],slot,width,height,
            spread && pages.total > 1 && !(cover && pages.first == 1),zoom,panX,panY)
    }
    private fun markLine() {
        val pages = current ?: return
        for (i in pages.pages.indices) {
            val area = area(pages,i)
            if (!area.contains(downX,downY)) continue
            val y = painter.crop.top / 100f + (downY - area.top) / area.height() *
                (1 - (painter.crop.top + painter.crop.bottom) / 100f)
            number = pages.first + i
            line = "$number/$y"
            onLine(line)
            onPlace(ReaderPlace(number.toString(),number.toDouble()/pages.total),true)
            lineUntil = android.os.SystemClock.uptimeMillis() + 2000
            invalidate()
            return
        }
    }
    private fun drawLine(canvas: Canvas) {
        val pages = current ?: return
        if (android.os.SystemClock.uptimeMillis() >= lineUntil) return
        val slot = (line.substringBefore('/').toIntOrNull() ?: return) - pages.first
        if (slot !in pages.pages.indices) return
        val area = area(pages,slot)
        val fraction = line.substringAfter('/').toFloatOrNull() ?: return
        val top = area.top + (fraction * 100 - painter.crop.top) / (100 - painter.crop.top - painter.crop.bottom) * area.height()
        canvas.drawRect(area.left,top-5*density,area.right,top+5*density,markerPaint)
        postInvalidateDelayed(160)
    }
    private val markerPaint = android.graphics.Paint().apply { color = 0x66efb667 }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoreGesture = animator != null || pendingJump != null || finishWhenReady
            candidateLine = false
            moved = false; multiple = false
            downX = e.x; downY = e.y
            lastX = e.x; lastY = e.y
            tracker?.recycle(); tracker = VelocityTracker.obtain()
        }
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !ignoreGesture) {
            candidateLine = e.pointerCount == 2
            secondX = e.getX(e.actionIndex); secondY = e.getY(e.actionIndex)
        }
        if (candidateLine && e.pointerCount == 2 && (abs(e.x-downX)>slop || abs(e.y-downY)>slop ||
                abs(e.getX(1)-secondX)>slop || abs(e.getY(1)-secondY)>slop)) candidateLine=false
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && candidateLine && e.eventTime-e.downTime < 400) {
            markLine(); candidateLine=false
        }
        if (!ignoreGesture && !columns) scale.onTouchEvent(e)
        tracker?.addMovement(e)
        val omit = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        var x = 0f; var y = 0f; var pointers = 0
        for (i in 0 until e.pointerCount) if (i != omit) {
            x += e.getX(i); y += e.getY(i); pointers++
        }
        x /= max(1, pointers); y /= max(1, pointers)
        val dx = x - downX
        val dy = y - downY
        if (!ignoreGesture) when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> { multiple = true; moved = true; resetTurn() }
            MotionEvent.ACTION_MOVE -> {
                if (abs(dx) > slop || abs(dy) > slop) moved = true
                if (multiple || (!columns && zoom > 1.01f)) {
                    if (moved) { panX += x - lastX; panY += y - lastY; invalidate() }
                } else if (!columns && moved && (direction != 0 || abs(dx) > abs(dy) * 1.25f)) {
                    begin(if (dx < 0) 1 else -1)
                    if (direction != 0) progress = (abs(dx) / max(1, width)).coerceIn(0f, 1f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (direction != 0) {
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val directedFling = -vx * direction >= 750 * density && abs(dx) >= max(slop * 2f, 28 * density)
                    settle(progress >= .25f || directedFling)
                } else if (columns && moved && !multiple && abs(dx) > width * .18f && abs(dx) > abs(dy)) {
                    navigate(ReaderAction(if (dx < 0) "next" else "previous"))
                } else if (!moved && !multiple) {
                    when (if (columns || zoom <= 1.01f) pageTapDirection(x / width.coerceAtLeast(1), tapZone, tapSide) else 0) {
                        -1 -> navigate(ReaderAction("previous"))
                        1 -> navigate(ReaderAction("next"))
                        else -> performClick()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> if (direction != 0) settle(false)
        }
        lastX = x; lastY = y
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker?.recycle(); tracker = null
        }
        return true
    }

    private fun columnSteps(): Int {
        val bitmap = current?.pages?.firstOrNull()?.bitmap ?: return 1
        val crop = painter.crop
        val ratio = (bitmap.height.toDouble() * (100 - crop.top - crop.bottom)) /
            (bitmap.width.toDouble() * (100 - crop.left - crop.right) / 2)
        return ceil(ratio * width / height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = current?.pages?.firstOrNull()?.bitmap
        if (columns && bitmap != null && width > 0 && height > 0) {
            val crop = painter.crop
            val left = bitmap.width * crop.left / 100f
            val top = bitmap.height * crop.top / 100f
            val half = bitmap.width * (100 - crop.left - crop.right) / 200f
            val contentHeight = bitmap.height * (100 - crop.top - crop.bottom) / 100f
            val steps = columnSteps()
            segment = segment.coerceIn(0, 2 * steps - 1)
            val scale = width / half
            val available = (contentHeight * scale - height).coerceAtLeast(0f)
            val offset = available * (segment % steps) / maxOf(1, steps - 1)
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.translate(-(left + half * (segment / steps)) * scale, -top * scale - offset)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(0, 0, bitmap.width, bitmap.height), null)
            canvas.restore()
            drawLine(canvas)
            return
        }
        val pages = current
        painter.draw(canvas, width, height, pages, adjacent(direction)?.let(nearby::get),
            spread && pages != null && pages.total > 1 && !(cover && pages.first == 1), mode, direction, progress, zoom, panX, panY)
        drawLine(canvas)
        panX = panX.coerceIn(painter.minPanX, painter.maxPanX)
        panY = panY.coerceIn(painter.minPanY, painter.maxPanY)
    }

    fun release() {
        scope.cancel(); resetTurn()
        tracker?.recycle(); tracker = null
        nearby.clear(); current = null
    }
}

@Composable
internal fun PdfPane(
    file: ReaderSource, initial: ReaderPlace?, zoom: Float, pageTurn: String, spread: Boolean, onZoom: (Float) -> Unit,
    onControl: (((ReaderAction) -> Unit)?) -> Unit, onPlace: (ReaderPlace, Boolean) -> Unit,
    onCount: (Int) -> Unit, onTap: () -> Unit, onError: (String) -> Unit,
    crop: PdfCrop, tapSide: String, tapZone: Int, onCropSampler: ((suspend () -> PdfCrop?)?) -> Unit, columns: Boolean = false, cover: Boolean = false, lineMarker: String = "", onLine: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val view = remember(file) { PdfCanvas(context, file, initial?.locator?.toIntOrNull() ?: 1) }
    DisposableEffect(view) {
        onControl(view::navigate)
        onCropSampler(view::suggestCrop)
        onDispose { onControl(null); onCropSampler(null); view.release() }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) { canvas ->
        canvas.onLine = onLine; canvas.line = lineMarker
        canvas.onZoom = onZoom; canvas.onPlace = onPlace; canvas.onCount = onCount
        canvas.onTap = onTap; canvas.onError = onError
        canvas.configure(zoom, pageTurn, spread, crop, tapSide, tapZone, columns, cover)
    }
}
