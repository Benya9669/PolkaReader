package ru.polka.reader.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.MessageDigest

class PolkaData private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: PolkaData? = null
        fun get(context: Context): PolkaData = instance ?: synchronized(this) {
            instance ?: PolkaData(context.applicationContext).also { instance = it }
        }
    }

    val db = Room.databaseBuilder(context, ReaderDatabase::class.java, "reader.db").build()
    private val dao = db.libraryDao()
    private val vault = SessionVault(context)
    private val transport = Transport()
    private val accounts = Mutex()
    private var unlocked: Credentials? = null
    private var generation = 0L
    private val current = MutableStateFlow(vault.account())
    val active = current.asStateFlow()
    private val fileLocks = Array(32) { Mutex() }
    private val initializedDirectories = mutableSetOf<String>()

    private fun account(scope: String) = active.value?.takeIf { it.scope == scope }
        ?: throw ApiError(401, JSONObject().put("detail", "Выберите учётную запись"))

    private fun credentialsLocked(scope: String): Credentials {
        val user = account(scope)
        unlocked?.let { return it }
        val secret = try { vault.decrypt(user) } catch (_: GeneralSecurityException) { null }
        if (secret == null) {
            if (user.authenticated) vault.clear(keepAccount = true)
            current.value = user.copy(authenticated = false)
            throw ApiError(401, JSONObject().put("detail", "Требуется повторный вход"))
        }
        return secret.also { unlocked = it }
    }

    private suspend fun <T> call(scope: String, method: String, path: String, body: JSONObject? = null,
                                 consume: (Response) -> T): T = withContext(Dispatchers.IO) {
        val session = accounts.withLock { credentialsLocked(scope) }
        try {
            transport.execute(transport.build(session.account.server, path, method, body, session), consume)
        } catch (e: ApiError) {
            if (e.status == 401) accounts.withLock {
                // An old request may finish after a fresh login, even for the same user.
                if (unlocked === session) {
                    vault.clear(keepAccount = true)
                    unlocked = null
                    current.value = session.account.copy(authenticated = false)
                }
            }
            throw e
        }
    }

    suspend fun login(server: String, username: String, password: String): Account = withContext(Dispatchers.IO) {
        val origin = serverAddress(server)
        val payload = JSONObject().put("username", username.trim()).put("password", password)
        val (principal, token) = transport.execute(transport.build(origin, "/auth/login", "POST", payload)) { response ->
            val cookie = Cookie.parseAll(response.request.url, response.headers).singleOrNull { it.name == "ebook_session" }
                ?: throw IOException("Сервер не выдал cookie ebook_session")
            if (cookie.secure && !response.request.url.isHttps) throw IOException("Сервер требует HTTPS для сеанса")
            profile(JSONObject(transport.json(response)), origin) to cookie.value
        }
        val session = Credentials(principal, token)
        val me = transport.execute(transport.build(origin, "/auth/me", "GET", null, session)) {
            profile(JSONObject(transport.json(it)), origin)
        }
        if (me.userId != principal.userId) throw IOException("Сервер подтвердил другую учётную запись")
        accounts.withLock {
            vault.save(me, token)
            generation++
            unlocked = Credentials(me, token)
            current.value = me
        }
        me
    }

    private fun profile(json: JSONObject, origin: String): Account {
        val id = json.getLong("id")
        return Account(accountScope(origin, id), origin, id, json.getString("username"), true,
            json.getBoolean("admin"), json.getBoolean("read_only"))
    }

    suspend fun verify(scope: String) {
        val activeAccount = account(scope)
        val checked = profile(request(scope, "GET", "/auth/me"), activeAccount.server)
        if (checked.userId != activeAccount.userId) throw IOException("Сервер подтвердил другую учётную запись")
    }

    suspend fun logout(scope: String): Unit = withContext(Dispatchers.IO) {
        val (started, session) = accounts.withLock {
            generation to if (account(scope).authenticated) credentialsLocked(scope) else null
        }
        try {
            if (session != null) transport.execute(
                transport.build(session.account.server, "/auth/logout", "POST", null, session), transport::json)
        } catch (error: ApiError) {
            if (error.status != 401) throw error
        } finally {
            accounts.withLock {
                if (active.value?.scope == scope && generation == started) {
                    vault.clear(keepAccount = false)
                    generation++
                    unlocked = null
                    current.value = null
                }
            }
        }
    }

    suspend fun request(scope: String, method: String, path: String, body: JSONObject? = null): JSONObject =
        JSONObject(call(scope, method, path, body, transport::json))

    private suspend fun list(scope: String, path: String) = JSONArray(call(scope, "GET", path, consume = transport::json))

    private suspend fun cached(scope: String, path: String): Pair<String, Boolean> {
        account(scope)
        val json = try {
            call(scope, "GET", path, consume = transport::json)
        } catch (e: IOException) {
            if (e is ApiError && e.status !in listOf(401, 502, 503, 504)) throw e
            return (dao.cached(scope, path) ?: throw e) to true
        }
        // Validate the container before replacing a usable offline response.
        if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json)
        dao.cache(CatalogEntry(scope, path, json))
        return json to false
    }

    suspend fun libraries(scope: String): List<Library> {
        val user = account(scope)
        return JSONArray(cached(scope, "/libraries").first).mapObjects {
            Library(it.getLong("id"), it.getString("name"),
                !user.readOnly && (user.admin || user.userId == it.getLong("owner_id")))
        }
    }

    suspend fun catalog(scope: String, query: String, libraryId: Long?, page: Int): BookPage {
        require(page in 1..10000 && query.length <= 300)
        val path = "/books?page=$page&limit=30&q=" + URLEncoder.encode(query.trim(), "UTF-8") +
            (libraryId?.let { "&library_id=$it" } ?: "")
        val (raw, offline) = cached(scope, path)
        val json = JSONObject(raw)
        val books = json.getJSONArray("items").mapObjects { it to parseBook(it) }
        if (!offline) books.forEach { (item, book) -> dao.cache(CatalogEntry(scope, "/books/" + book.id, item.toString())) }
        return BookPage(books.map { it.second }, json.getBoolean("has_next"), offline)
    }

    suspend fun book(scope: String, id: Long) = parseBook(JSONObject(cached(scope, "/books/$id").first))

    suspend fun bookForFile(scope: String, fileId: Long): Book {
        val info = request(scope, "GET", "/files/$fileId/info")
        return book(scope, info.getLong("work_id"))
    }

    suspend fun home(scope: String): List<ReadingBook> =
        JSONObject(cached(scope, "/reading/home").first).getJSONArray("reading").mapObjects { item ->
            val state = item.getJSONObject("state")
            ReadingBook(parseBook(item.getJSONObject("book")), state.getLong("last_file_id"),
                state.optDouble("percentage", 0.0), state.stringOrNull("chapter"), state.stringOrNull("last_read"))
        }

    suspend fun startConversion(scope: String, fileId: Long) = parseConversion(request(scope, "POST", "/conversions/$fileId"))
    suspend fun conversion(scope: String, jobId: Long): Conversion? = list(scope, "/imports").mapObjects(::parseConversion)
        .firstOrNull { it.id == jobId }

    suspend fun bookmarks(scope: String, fileId: Long) = list(scope, "/reading/bookmarks/$fileId").mapObjects(::parseBookmark)
    suspend fun addBookmark(scope: String, fileId: Long, locator: String, label: String) =
        parseBookmark(request(scope, "POST", "/reading/bookmarks/$fileId", JSONObject().put("locator", locator).put("label", label)))
    suspend fun deleteBookmark(scope: String, fileId: Long, id: Long) { request(scope, "DELETE", "/reading/bookmarks/$fileId/$id") }
    suspend fun annotations(scope: String, fileId: Long) = list(scope, "/reading/annotations/$fileId").mapObjects(::parseAnnotation)
    suspend fun addAnnotation(scope: String, fileId: Long, locator: String, quote: String, note: String) =
        parseAnnotation(request(scope, "POST", "/reading/annotations/$fileId",
            JSONObject().put("locator", locator).put("quote", quote).put("note", note)))
    suspend fun updateAnnotation(scope: String, fileId: Long, id: Long, note: String) =
        parseAnnotation(request(scope, "PATCH", "/reading/annotations/$fileId/$id", JSONObject().put("note", note)))
    suspend fun deleteAnnotation(scope: String, fileId: Long, id: Long) { request(scope, "DELETE", "/reading/annotations/$fileId/$id") }

    fun downloads(scope: String) = dao.downloads(scope)
    fun lastOpened(scope: String) = dao.lastOpened(scope)

    suspend fun markOpened(scope: String, fileId: Long) {
        account(scope)
        dao.markOpened(scope, fileId, System.currentTimeMillis())
    }

    private fun directory(scope: String): File {
        account(scope)
        return File(context.noBackupFilesDir, "books/$scope").also { dir ->
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Не удалось создать папку для книг")
            synchronized(initializedDirectories) {
                // In a new process these partial transfers have no live writer.
                if (initializedDirectories.add(scope)) dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
            }
        }
    }

    private fun lock(scope: String, id: Long) = fileLocks[(scope.hashCode() xor id.hashCode()) and (fileLocks.size - 1)]

    suspend fun download(scope: String, book: Book, edition: Edition, file: BookFile,
                         onProgress: (Long, Long) -> Unit = { _, _ -> }): Download = withContext(Dispatchers.IO) {
        require(file.readable && edition.files.contains(file) && book.editions.contains(edition))
        lock(scope, file.id).withLock {
            val dir = directory(scope)
            val old = dao.download(scope, file.id)
            if (old != null && File(old.path).let { it.isFile && it.length() == file.size }) return@withLock old
            val tmp = File.createTempFile("download-", ".part", dir)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val count = call(scope, "GET", "/files/" + file.id) { response ->
                    val input = response.body?.byteStream() ?: throw IOException("Сервер вернул пустой файл")
                    var bytes = 0L
                    var lastUpdate = 0L
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        onProgress(0, file.size)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            if (bytes > file.size) throw IOException("Размер загрузки не совпадает с каталогом")
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 120 || bytes == file.size) {
                                onProgress(bytes, file.size)
                                lastUpdate = now
                            }
                        }
                        output.fd.sync()
                    }
                    bytes
                }
                if (count != file.size || !file.sha256.equals(digest.digest().hex(), ignoreCase = true))
                    throw IOException("Скачанный файл не прошёл проверку размера или SHA-256. Повторите загрузку")
                account(scope)
                val dest = File(dir, "${file.id}.${file.format}")
                if (!tmp.renameTo(dest)) throw IOException("Не удалось сохранить файл на устройстве")
                Download(scope, file.id, book.id, edition.id, book.title, book.authors, file.name, file.format,
                    dest.absolutePath, count).also { dao.save(it) }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    suspend fun remove(scope: String, fileId: Long) = withContext(Dispatchers.IO) {
        lock(scope, fileId).withLock {
            account(scope)
            val item = dao.download(scope, fileId) ?: return@withLock
            val local = File(item.path)
            dao.remove(scope, fileId)
            if (local.exists() && !local.delete()) {
                dao.save(item)
                throw IOException("Не удалось удалить локальную копию")
            }
        }
    }

    suspend fun cover(scope: String, editionId: Long): File? = withContext(Dispatchers.IO) {
        val dir = directory(scope)
        val image = File(dir, "cover-$editionId")
        if (image.isFile) return@withContext image
        val temp = File.createTempFile("cover-", ".part", dir)
        try {
            call(scope, "GET", "/editions/$editionId/cover") {
                val source = it.body?.source() ?: throw IOException("Обложка недоступна")
                if (source.request(12L * 1024 * 1024 + 1)) throw IOException("Обложка слишком велика")
                temp.outputStream().use { output -> source.inputStream().copyTo(output) }
            }
            if (!temp.renameTo(image)) throw IOException("Не удалось сохранить обложку")
            image
        } catch (e: ApiError) {
            if (e.status == 404) null else throw e
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
