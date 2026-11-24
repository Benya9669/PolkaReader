package ru.polka.reader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val ORIGIN = "https://reader.invalid"
private const val ENTRY = "$ORIGIN/reader/index.html"

private fun BookSettings.toJson() = JSONObject().put("fontPercent", fontPercent)
    .put("lineHeight", lineHeight.toDouble()).put("marginDp", marginDp).put("theme", theme)

private fun ReaderPlace.toJson() = JSONObject().put("locator", locator).put("percentage", percentage).put("chapter", chapter)

/** This local origin has exactly four assets and the selected private book. Every other request gets a local 404. */
private class PublicationClient(private val context: Context, private val book: File, val loaded: (WebView) -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        val url = request.url
        if (request.method == "GET" && url.scheme == "https" && url.encodedAuthority == "reader.invalid") {
            val path = url.encodedPath
            try {
                when (path) {
                    "/reader/publication.epub" -> return WebResourceResponse("application/epub+zip", null, book.inputStream())
                    "/reader/index.html", "/reader/epub.min.js", "/reader/jszip.min.js", "/reader/reader.js" -> {
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
    file: File, initial: ReaderPlace?, style: BookSettings, highlights: List<String>,
    onControl: (((ReaderAction) -> Unit)?) -> Unit, onPlace: (ReaderPlace, Boolean) -> Unit,
    onChapters: (List<Chapter>) -> Unit, onSelection: (BookSelection) -> Unit,
    onTap: () -> Unit, onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentStyle by rememberUpdatedState(style)
    val currentHighlights by rememberUpdatedState(highlights)
    val place by rememberUpdatedState(onPlace)
    val tap by rememberUpdatedState(onTap)
    val select by rememberUpdatedState(onSelection)
    val chapters by rememberUpdatedState(onChapters)
    val error by rememberUpdatedState(onError)
    val control by rememberUpdatedState(onControl)
    var port by remember { mutableStateOf<WebMessagePort?>(null) }
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
                                    .put("settings", currentStyle.toJson()).put("highlights", JSONArray(currentHighlights)))
                                control { action -> send(JSONObject().put("type", action.type)
                                    .put("locator", action.locator).put("percentage", action.percentage).put("persist", action.persist)) }
                            }
                            "place" -> {
                                val locator = event.optString("locator")
                                val percent = event.optDouble("percentage")
                                if (locator.length <= 4000 && locator.startsWith("epubcfi(") && locator.endsWith(")") && percent in 0.0..1.0) {
                                    place(ReaderPlace(locator, percent,
                                        if (event.isNull("chapter")) null else event.optString("chapter").take(500)),
                                        event.optBoolean("persist"))
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
    LaunchedEffect(style) { send(JSONObject().put("type", "style").put("settings", style.toJson())) }
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
