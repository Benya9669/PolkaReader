package ru.polka.reader.sync

import org.json.JSONObject
import java.io.IOException

internal data class ServerProgress(val version: Long, val point: ReadingPoint?, val alternate: Alternate? = null)

internal fun isReadingLocator(locator: String): Boolean {
    if (locator.length !in 1..4000) return false
    if (locator.startsWith("epubcfi(") && locator.endsWith(")")) return true
    return locator.all { it in '0'..'9' } && (locator.toIntOrNull() ?: 0) in 1..999999
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.fraction(): Double = optDouble("percentage", Double.NaN).also {
    if (it !in 0.0..1.0) throw IOException("Сервер вернул недопустимый процент чтения")
}

internal fun JSONObject.serverProgress(): ServerProgress {
    val version = optLong("version", -1L)
    if (version < 0) throw IOException("Сервер не указал версию позиции")
    val locator = stringOrNull("locator")
    val point = if (locator != null && isReadingLocator(locator))
        ReadingPoint(locator, fraction(), stringOrNull("chapter"), stringOrNull("device"), stringOrNull("updated"))
    else if (version == 0L && locator == null) null
    else throw IOException("Сервер вернул недопустимую позицию")
    val alternate = optJSONObject("alternate")?.let {
        val id = it.optLong("file_id", -1)
        val format = it.stringOrNull("format")
        if (id <= 0 || format.isNullOrBlank()) throw IOException("Сервер вернул неизвестное издание")
        Alternate(id, format, it.fraction(), it.stringOrNull("updated"))
    }
    return ServerProgress(version, point, alternate)
}

internal fun ProgressRow.payload(): JSONObject = JSONObject().apply {
    put("version", serverVersion)
    put("locator", point.locator)
    put("percentage", point.percentage)
    put("device", point.device)
    point.chapter?.let { put("chapter", it) }
}
