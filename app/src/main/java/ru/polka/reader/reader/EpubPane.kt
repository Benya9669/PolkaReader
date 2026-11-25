package ru.polka.reader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import ru.polka.reader.data.ReaderSource
import java.io.IOException
import java.util.concurrent.CancellationException

private const val ORIGIN = "https://reader.invalid"
private const val ENTRY = "$ORIGIN/reader/index.html"

private fun BookSettings.toJson() = JSONObject().put("fontPercent", fontPercent)
    .put("lineHeight", lineHeight.toDouble()).put("marginDp", marginDp).put("theme", theme).put("pageTurn", pageTurn)
    .put("fontFamily", fontFamily).put("tapSide", tapSide).put("tapZone", tapZone)
    .put("chapterHint", chapterHint).put("scrollBook", scrollBook).put("hyphenate", hyphenate).put("alignment", alignment)

private fun ReaderPlace.toJson() = JSONObject().put("locator", locator).put("percentage", percentage).put("chapter", chapter)

private fun JSONObject.toPlace(): ReaderPlace? {
    val locator = optString("locator")
    val percentage = optDouble("percentage")
    if (locator.length > 4000 || !locator.startsWith("epubcfi(") || !locator.endsWith(")") || percentage !in 0.0..1.0) return null
    return ReaderPlace(locator, percentage, if (isNull("chapter")) null else optString("chapter").take(500))
}

private data class Footnote(val text: String, val target: String)

/** App scripts and the publication use separate synthetic origins. Neither forwards requests to the network. */
private class PublicationClient(private val context: Context, private val book: ReaderSource, val loaded: (WebView) -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        if (request.method == "GET" && url.scheme == "https" && url.encodedAuthority == "book.invalid") {
            val name = url.path?.removePrefix("/").orEmpty()
            try {
                val bytes = book.archive?.entry(name)
                if (bytes != null) {
                    val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                    val mime = when (extension) {
                        "opf", "ncx", "xml" -> "application/xml"
                        "html", "htm" -> "text/html"
                        "xhtml" -> "application/xhtml+xml"
                        "svg" -> "image/svg+xml"
                        "css" -> "text/css"
                        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                    }
                    return WebResourceResponse(mime, null, 200, "OK", mapOf("Access-Control-Allow-Origin" to ORIGIN,
                        "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), bytes.inputStream())
                }
            } catch (_: IOException) { /* The reader handles a failed resource load. */ }
              catch (_: CancellationException) { /* The book was closed. */ }
            return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to ORIGIN, "Cache-Control" to "no-store"), byteArrayOf().inputStream())
        }
        if (request.method == "GET" && url.scheme == "https" && url.encodedAuthority == "reader.invalid") {
            val path = url.encodedPath
            try {
                when (path) {
                    "/reader/publication.epub" -> return WebResourceResponse("application/epub+zip", null, book.epubStream())
                    "/reader/index.html", "/reader/epub.min.js", "/reader/jszip.min.js", "/reader/reader-tools.js", "/reader/reader.js" -> {
                        val mime = if (path.endsWith(".html")) "text/html" else "text/javascript"
                        return WebResourceResponse(mime, "UTF-8", context.assets.open(path.removePrefix("/")))
                    }
                }
            } catch (_: IOException) {
                // A concurrently removed/damaged copy is reported by the renderer as a failed fetch.
            }
        }
        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), byteArrayOf().inputStream())
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
        request.isForMainFrame || request.url.scheme !in setOf("blob", "about")

    override fun onPageFinished(view: WebView, url: String) { if (url == ENTRY) loaded(view) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EpubPane(
    file: ReaderSource, initial: ReaderPlace?, style: BookSettings, highlights: List<String>,
    onControl: (((ReaderAction) -> Unit)?) -> Unit, onPlace: (ReaderPlace, Boolean) -> Unit,
    onChapters: (List<Chapter>) -> Unit, onSelection: (BookSelection) -> Unit,
    onTap: () -> Unit, onError: (String) -> Unit,
    spread: Boolean = false, onLinkNavigate: (ReaderPlace) -> Unit = {},
    inspection: ReaderInspection, onRemainder: (String) -> Unit, onIllustration: (Boolean) -> Unit,
    lineMarker: String = "", onLine: (String) -> Unit = {},
    onRepairPercentage: (ReaderPlace) -> Unit = {},
) {
    val context = LocalContext.current
    val currentStyle by rememberUpdatedState(style)
    val currentHighlights by rememberUpdatedState(highlights)
    val currentSpread by rememberUpdatedState(spread)
    val linkNavigate by rememberUpdatedState(onLinkNavigate)
    val showHint by rememberUpdatedState(onRemainder)
    val saveLine by rememberUpdatedState(onLine)
    val repairPercentage by rememberUpdatedState(onRepairPercentage)
    val showImage by rememberUpdatedState(onIllustration)
    val place by rememberUpdatedState(onPlace)
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(onSelection)
    val chapters by rememberUpdatedState(onChapters)
    val error by rememberUpdatedState(onError)
    val control by rememberUpdatedState(onControl)
    var port by remember { mutableStateOf<WebMessagePort?>(null) }
    var footnote by remember(file) { mutableStateOf<Footnote?>(null) }
    fun send(value: JSONObject) { port?.postMessage(WebMessage(value.toString())) }

    val web = remember(file) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = PublicationClient(context.applicationContext, file) { view ->
                val ports = view.createWebMessageChannel()
                ports[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(channel: WebMessagePort, message: WebMessage) {
                        val event = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return
                        when (event.optString("type")) {
                            "connected" -> {
                                port = channel
                                send(JSONObject().put("type", "open").put("initial", initial?.toJson())
                                    .put("line", lineMarker).put("streaming", file.archive != null)
                                    .put("settings", currentStyle.toJson()).put("spread", currentSpread).put("highlights", JSONArray(currentHighlights)))
                                control { action -> send(JSONObject().put("type", action.type)
                                    .put("locator", action.locator).put("percentage", action.percentage).put("persist", action.persist)
                                    .put("id", action.requestId).put("query", action.query)) }
                            }
                            "line" -> event.optString("locator").takeIf { it.startsWith("epubcfi(") && it.length <= 4000 }?.let(saveLine)
                            "repairPercentage" -> event.toPlace()?.let(repairPercentage)
                            "place" -> event.toPlace()?.let { place(it, event.optBoolean("persist")) }
                            "searchResults", "positionPreview" -> inspection.receive(event)
                            "chapterRemainder" -> showHint(when {
                                event.has("pages") -> quantityHint(event.optInt("pages"), false)
                                event.has("minutes") -> quantityHint(event.optInt("minutes"), true)
                                else -> ""
                            })
                            "illustration" -> showImage(event.optBoolean("open"))
                            "linkOrigin" -> event.toPlace()?.let(linkNavigate)
                            "footnote" -> {
                                val target = event.optString("target")
                                val text = event.optString("text").take(8000)
                                if (text.isNotBlank() && target.length in 1..4000) {
                                    footnote = Footnote(text, target)
                                }
                            }
                            "chapters" -> {
                                val items = event.optJSONArray("items") ?: return
                                chapters((0 until items.length()).mapNotNull { i ->
                                    items.optJSONObject(i)?.let { Chapter(it.optString("label"), it.optString("href"), it.optInt("depth")) }
                                })
                            }
                            "selection" -> {
                                val locator = event.optString("locator")
                                val quote = event.optString("quote").take(4000)
                                if (locator.startsWith("epubcfi(") && locator.length <= 4000 && quote.isNotBlank()) {
                                    select(BookSelection(locator, quote))
                                }
                            }
                            "tap" -> tap()
                            "error" -> error(event.optString("message", "Не удалось прочитать EPUB."))
                        }
                    }
                })
                view.postWebMessage(WebMessage("polka-reader", arrayOf(ports[1])), Uri.parse(ORIGIN))
            }
            loadUrl(ENTRY)
        }
    }
    AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    footnote?.let { note ->
        AlertDialog(
            onDismissRequest = { footnote = null }, title = { Text("Сноска") },
            text = { Text(note.text, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { footnote = null }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(onClick = {
                    footnote = null
                    send(JSONObject().put("type", "link").put("locator", note.target))
                }) { Text("Перейти к сноске") }
            },
        )
    }
    LaunchedEffect(style, spread) { send(JSONObject().put("type", "style").put("settings", style.toJson()).put("spread", spread)) }
    LaunchedEffect(highlights) { send(JSONObject().put("type", "highlights").put("locators", JSONArray(highlights))) }
    DisposableEffect(web) {
        onDispose {
            port?.close()
            port = null
            control(null)
            web.stopLoading()
            web.destroy()
        }
    }
}
