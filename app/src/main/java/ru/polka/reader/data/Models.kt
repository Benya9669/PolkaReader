package ru.polka.reader.data

import org.json.JSONArray
import org.json.JSONObject

data class Account(
    val scope: String,
    val server: String,
    val userId: Long,
    val username: String,
    val authenticated: Boolean,
    val admin: Boolean = false,
    val readOnly: Boolean = false,
)

data class Library(val id: Long, val name: String, val canConvert: Boolean)

data class Book(
    val id: Long,
    val title: String,
    val authors: String,
    val libraryId: Long,
    val editions: List<Edition>,
)

data class Edition(
    val id: Long,
    val label: String,
    val summary: String,
    val hasCover: Boolean,
    val files: List<BookFile>,
)

data class BookFile(
    val id: Long,
    val name: String,
    val format: String,
    val size: Long,
    val derivedFrom: Long?,
    val sha256: String,
) {
    val readable: Boolean get() = format == "epub" || format == "pdf"
    val conversionTarget: String? get() = when (format) {
        "fb2", "fb2.zip", "txt", "mobi", "azw3", "lrf", "cbz", "html", "rtf" -> "EPUB"
        "djvu" -> "PDF"
        else -> null
    }
}

data class BookPage(val items: List<Book>, val hasNext: Boolean, val cached: Boolean = false)
data class ReadingBook(val book: Book, val fileId: Long, val percentage: Double, val chapter: String?, val updated: String?)
data class Conversion(val id: Long, val status: String, val fileId: Long?, val error: String?)
data class Bookmark(val id: Long, val locator: String, val label: String)
data class AnnotationEntry(val id: Long, val locator: String, val quote: String, val note: String)

internal fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
internal fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
internal fun <T> JSONArray.mapObjects(convert: (JSONObject) -> T) = List(length()) { convert(getJSONObject(it)) }

internal fun parseBook(json: JSONObject): Book = with(json) {
    val names = getJSONArray("authors")
    Book(getLong("id"), getString("title"), List(names.length()) { names.getString(it) }.joinToString(", "),
        getLong("library_id"), getJSONArray("editions").mapObjects { edition ->
            val id = edition.getLong("id")
            val label = listOf("publisher", "year", "language", "isbn").mapNotNull { edition.stringOrNull(it) }
                .filter(String::isNotBlank).joinToString(" · ").ifBlank { "Издание №" + id }
            Edition(id, label, edition.optString("summary"), !edition.isNull("cover"),
                edition.getJSONArray("files").mapObjects { file ->
                    with(file) { BookFile(getLong("id"), getString("name"), getString("format"), getLong("size"),
                        longOrNull("derived_from"), getString("sha256")) }
                })
        })
}

internal fun parseConversion(json: JSONObject) = with(json) {
    Conversion(getLong("id"), getString("status"), optJSONObject("result")?.longOrNull("file_id"), stringOrNull("error"))
}
internal fun parseBookmark(json: JSONObject) = with(json) { Bookmark(getLong("id"), getString("locator"), getString("label")) }
internal fun parseAnnotation(json: JSONObject) = with(json) {
    AnnotationEntry(getLong("id"), getString("locator"), getString("quote"), getString("note"))
}
