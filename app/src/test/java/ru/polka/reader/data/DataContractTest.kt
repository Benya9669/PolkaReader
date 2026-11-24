package ru.polka.reader.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class DataContractTest {
    @Test fun scopesIncludeOriginMountAndServerUserId() {
        val canonical = serverAddress(" HTTPS://EXAMPLE.org:443/polka/ ")
        assertEquals("https://example.org/polka", canonical)
        val a = accountScope(canonical, 7)
        assertEquals(64, a.length)
        assertNotEquals(a, accountScope("https://example.org:8443/polka", 7))
        assertNotEquals(a, accountScope("https://example.org/other", 7))
        assertNotEquals(a, accountScope(canonical, 8))
        for (bad in listOf("example.org", "file:///books", "https://a@b", "https://b?password=x", "https://b#x")) {
            assertTrue("Invalid address was accepted", runCatching { serverAddress(bad) }.isFailure)
        }
    }

    @Test fun plainHttpLimitedToLanPeers() {
        for (ip in listOf("127.0.0.1", "10.0.2.2", "172.16.2.1", "192.168.1.3", "169.254.1.2", "::1", "fd12::1")) {
            assertTrue(ip, isLanAddress(InetAddress.getByName(ip)))
        }
        for (ip in listOf("8.8.8.8", "172.32.2.1", "2001:4860:4860::8888", "0.0.0.0")) {
            assertFalse(ip, isLanAddress(InetAddress.getByName(ip)))
        }
    }

    @Test fun derivedFileRetainsOwnIdentityWithinEdition() {
        val json = JSONObject("""{
            "id":30,"library_id":2,"title":"Проверка","authors":["Первый","Второй"],
            "editions":[{"id":100,"cover":null,"summary":"","publisher":"","year":"","language":"ru",
                "files":[{"id":12,"format":"fb2","size":42,"name":"source.fb2","sha256":"abc","derived_from":null},
                         {"id":19,"format":"epub","size":55,"name":"read.epub","sha256":"def","derived_from":12}]}]
        }""")
        val book = parseBook(json)
        val edition = book.editions.single()
        assertEquals("Первый, Второй", book.authors)
        assertEquals("ru", edition.label)
        assertEquals("EPUB", edition.files.first().conversionTarget)
        val copy = edition.files.single { it.readable }
        assertEquals(19L, copy.id)
        assertEquals(12L, copy.derivedFrom)
        val done = parseConversion(JSONObject("""{"id":9,"status":"done","result":{"file_id":19},"error":null}"""))
        assertEquals(copy.id, done.fileId)
    }

    @Test fun scopedCookieAndOptimisticConflictTravelOverHttp() = runBlocking {
        val address = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, address).use { server ->
            val headers = AtomicReference<List<String>>()
            val received = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = generateSequence { reader.readLine() }.takeWhile(String::isNotEmpty).toList()
                    headers.set(lines)
                    val length = lines.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                    repeat(length) { reader.read() }
                    val body = """{"current":{"version":4,"locator":"17"}}"""
                    val reply = "HTTP/1.1 409 Conflict\r\nContent-Type: application/json\r\nContent-Length: " +
                        body.length + "\r\nConnection: close\r\n\r\n" + body
                    socket.getOutputStream().apply { write(reply.toByteArray(Charsets.US_ASCII)); flush() }
                }
            }.apply { isDaemon = true; start() }
            val origin = "http://127.0.0.1:" + server.localPort
            val token = UUID.randomUUID().toString()
            val session = Credentials(Account(accountScope(origin, 7), origin, 7, "reader", true), token)
            val api = Transport()
            val request = api.build(origin, "/reading/progress/9", "PUT", JSONObject().put("version", 3), session)
            val error = runCatching { api.execute(request, api::json) }.exceptionOrNull()
            received.join(2000)
            assertTrue(error is ApiError)
            assertEquals(409, (error as ApiError).status)
            assertEquals("17", error.json.getJSONObject("current").getString("locator"))
            assertEquals("PUT /api/v1/reading/progress/9 HTTP/1.1", headers.get().first())
            assertTrue(headers.get().any { it == "X-Polka-User: 7" })
            assertTrue(headers.get().any { it == "Cookie: ebook_session=$token" })
            assertTrue(runCatching { api.build("http://localhost:8080", "/books", "GET", null, session) }.isFailure)
        }
    }
}
