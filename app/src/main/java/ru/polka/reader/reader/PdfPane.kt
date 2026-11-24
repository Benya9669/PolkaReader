package ru.polka.reader.reader

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

private class PdfCanvas(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var bitmap: Bitmap? = null
    var pageNumber = 0
    var zoom = 1f
    var onTap: () -> Unit = {}
    var onZoom: (Float) -> Unit = {}
    var turn: (Boolean) -> Unit = {}
    private var panX = 0f
    private var panY = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(.65f, 3f)
            onZoom(zoom)
            invalidate()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapConfirmed(event: MotionEvent) = performClick()
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!scale.isInProgress) { panX -= dx; panY -= dy; invalidate() }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null || zoom > 1.01f || abs(vx) < abs(vy) * 1.5f || abs(e2.x - e1.x) < width * .12f) return false
            turn(vx < 0)
            return true
        }
    })
    init { isClickable = true; isFocusable = true }
    override fun performClick(): Boolean { super.performClick(); onTap(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }
    fun resetPan() { panX = 0f; panY = 0f }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(64,65,64))
        val image = bitmap ?: return
        val factor = min(width.toFloat() / image.width, height.toFloat() / image.height) * zoom
        val w = image.width * factor
        val h = image.height * factor
        val maxX = max(0f, (w - width) / 2)
        val maxY = max(0f, (h - height) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        val left = (width - w) / 2 + panX
        val top = (height - h) / 2 + panY
        canvas.drawBitmap(image, null, RectF(left, top, left + w, top + h), paint)
    }
}

private data class PageImage(val number: Int, val count: Int, val bitmap: Bitmap)

private fun renderPage(file: File, number: Int, width: Int): PageImage =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).let { descriptor ->
        val document = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
        document.use {
            require(number in 1..document.pageCount)
            document.openPage(number - 1).use { page ->
                // Cap allocation for unusually tall or wide PDFs. Drawing and gestures are independent of raster size.
                val factor = minOf(width * 1.8 / page.width,
                    sqrt(4_000_000.0 / (page.width.toDouble() * page.height)),
                    4096.0 / max(page.width, page.height))
                val bitmap = Bitmap.createBitmap(maxOf(1, (factor * page.width).roundToInt()),
                    maxOf(1, (factor * page.height).roundToInt()), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                PageImage(number, document.pageCount, bitmap)
            }
        }
    }

@Composable
internal fun PdfPane(
    file: File, initial: ReaderPlace?, zoom: Float, onZoom: (Float) -> Unit,
    onControl: (((ReaderAction) -> Unit)?) -> Unit, onPlace: (ReaderPlace, Boolean) -> Unit,
    onCount: (Int) -> Unit, onTap: () -> Unit, onError: (String) -> Unit,
) {
    var number by remember { mutableIntStateOf(initial?.locator?.toIntOrNull() ?: 1) }
    var image by remember { mutableStateOf<PageImage?>(null) }
    var persist by remember { mutableStateOf(initial == null) }
    val currentPlace by rememberUpdatedState(onPlace)
    val currentCount by rememberUpdatedState(onCount)
    val currentError by rememberUpdatedState(onError)
    val navigate by rememberUpdatedState<(ReaderAction) -> Unit> { action ->
        val total = image?.count ?: 0
        if (total > 0) {
            val target = when (action.type) {
                "next" -> number + 1
                "previous" -> number - 1
                else -> action.locator?.toIntOrNull() ?: action.percentage?.let { ceil(it * total).toInt() } ?: number
            }.coerceIn(1, total)
            if (target != number) { persist = action.persist; number = target }
            else if (action.type == "jump") currentPlace(ReaderPlace("$number", number.toDouble() / total), action.persist)
        }
    }
    DisposableEffect(file) {
        onControl { navigate(it) }
        onDispose { onControl(null) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(LocalDensity.current) { maxWidth.roundToPx() }
        LaunchedEffect(file, number, width) {
            if (width <= 0) return@LaunchedEffect
            try {
                val rendered = withContext(Dispatchers.IO) { renderPage(file, number, width) }
                image = rendered
                currentCount(rendered.count)
                currentPlace(ReaderPlace("${rendered.number}", rendered.number.toDouble() / rendered.count), persist)
                persist = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { currentError("Не удалось открыть эту страницу PDF. Проверьте локальную копию и номер страницы.") }
        }
        AndroidView(factory = { PdfCanvas(it) }, modifier = Modifier.fillMaxSize()) { canvas ->
            val page = image
            if (page != null && canvas.pageNumber != page.number) canvas.resetPan()
            canvas.pageNumber = page?.number ?: 0
            canvas.bitmap = page?.bitmap
            canvas.zoom = zoom
            canvas.onZoom = onZoom
            canvas.onTap = onTap
            canvas.turn = { next -> navigate(ReaderAction(if (next) "next" else "previous")) }
            canvas.contentDescription = page?.let { "Страница ${it.number} из ${it.count}. Нажмите, чтобы показать или скрыть панели." }
            canvas.invalidate()
        }
    }
}
