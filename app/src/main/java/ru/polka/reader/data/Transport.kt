package ru.polka.reader.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ApiError(val status: Int, val json: JSONObject) : IOException(
    json.stringOrNull("detail")?.take(250) ?: "Ошибка сервера HTTP $status"
)

internal class Transport {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()

    // Validate the DNS answer used by the socket. No proxy or redirect can carry the LAN cookie off-origin.
    private val lanClient = client.newBuilder().proxy(Proxy.NO_PROXY).dns(object : Dns {
        override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).filter(::isLanAddress).ifEmpty {
            throw UnknownHostException("HTTP разрешён только в локальной сети. Для внешнего адреса используйте HTTPS")
        }
    }).addNetworkInterceptor { chain ->
        val peer = chain.connection()?.socket()?.inetAddress
        if (peer == null || !isLanAddress(peer)) throw IOException("HTTP разрешён только в локальной сети")
        chain.proceed(chain.request())
    }.build()

    fun build(server: String, path: String, method: String, body: JSONObject?, credentials: Credentials? = null): Request {
        require(path.startsWith("/") && !path.startsWith("//"))
        val builder = Request.Builder().url(server + "/api/v1" + path)
        credentials?.let {
            require(it.account.server == server)
            builder.header("Cookie", "ebook_session=" + it.cookie).header("X-Polka-User", it.account.userId.toString())
        }
        val payload = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        return builder.method(method, payload).build()
    }

    suspend fun <T> execute(request: Request, consume: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = (if (request.url.isHttps) client else lanClient).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) {
                            val info = runCatching { JSONObject(it.peekBody(16384L).string()) }.getOrElse { JSONObject() }
                            throw ApiError(it.code, info)
                        }
                        consume(it)
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    fun json(response: Response): String {
        if (response.code == 204) return "{}"
        val source = response.body?.source() ?: throw IOException("Сервер вернул пустой ответ")
        if (source.request(32L * 1024 * 1024 + 1)) throw IOException("Ответ сервера слишком велик")
        return source.readUtf8()
    }
}
