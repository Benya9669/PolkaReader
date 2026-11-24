package ru.polka.reader.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.security.MessageDigest

internal fun serverAddress(input: String): String {
    val raw = input.trim()
    require(raw.startsWith("https://", true) || raw.startsWith("http://", true)) {
        "Введите полный адрес сервера с http:// или https://"
    }
    val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("Некорректный адрес сервера")
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Адрес сервера должен быть без логина, пароля, запроса и фрагмента"
    }
    return url.toString().trimEnd('/')
}

internal fun isLanAddress(ip: InetAddress): Boolean = ip.isLoopbackAddress || ip.isSiteLocalAddress ||
    ip.isLinkLocalAddress || (ip.address.size == 16 && (ip.address[0].toInt() and 0xfe) == 0xfc)

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun accountScope(server: String, id: Long): String =
    MessageDigest.getInstance("SHA-256").digest(("$server\n$id").toByteArray(Charsets.UTF_8)).hex()
