package com.poslik.pos.core.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RestRealtimeDbGatewayTest {

    private lateinit var server: HttpServer
    private val requests = mutableListOf<RecordedRequest>()
    private var nextResponse: (HttpExchange) -> Unit = { it.reply(200, "{}") }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val authorization: String?,
        val body: String,
    )

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val record = RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                query = exchange.requestURI.rawQuery,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            requests.add(record)
            exchange.use { nextResponse(exchange) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun databaseUrl(): String = "http://127.0.0.1:${server.address.port}"

    private fun gateway(auth: RealtimeDbAuth = NoAuth) =
        RestRealtimeDbGateway(databaseUrl(), auth, RestRealtimeDbGateway.defaultHttpClient())

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
        responseBody.flush()
    }

    @Test
    fun `a multi location patch posts the expected url and body`() = runBlocking {
        nextResponse = { it.reply(200, """{"ok":true}""") }
        val gateway = gateway()

        gateway.patch(
            "stores/boutique",
            linkedMapOf(
                "sales/abc" to buildJsonObject {
                    put("id", "abc")
                    put("updatedAt", RealtimePaths.serverTimestampSentinel())
                },
                "ticketIndex/T-ABCDEF-000001" to JsonPrimitive("abc"),
            ),
            printSilent = true,
        )

        val request = requests.single()
        assertEquals("PATCH", request.method)
        assertEquals("/stores/boutique.json", request.path)
        assertEquals("print=silent", request.query)
        val body = Json.parseToJsonElement(request.body) as JsonObject
        assertEquals("abc", (body["sales/abc"] as JsonObject)["id"]!!.jsonPrimitive.content)
        assertEquals(
            "timestamp",
            ((body["sales/abc"] as JsonObject)["updatedAt"] as JsonObject)[".sv"]!!.jsonPrimitive.content,
        )
        assertEquals("abc", body["ticketIndex/T-ABCDEF-000001"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the server timestamp uses the REST server value placeholder`() {
        // `ServerValue.TIMESTAMP` est un symbole du SDK client : envoye en REST, Firebase
        // repond 400 et n'ecrit rien. Seul `{".sv":"timestamp"}` est resolu par le serveur.
        assertEquals(
            buildJsonObject { put(".sv", JsonPrimitive("timestamp")) },
            RealtimePaths.serverTimestampSentinel(),
        )
    }

    @Test
    fun `the json extension is placed before the query string`() {
        nextResponse = { it.reply(200, "{}") }
        val gateway = gateway()

        runBlocking { gateway.patch("stores/boutique", mapOf("a" to JsonPrimitive("b")), printSilent = true) }

        // `/stores/boutique?print=silent.json` (extension apres le ?) renvoie 405.
        assertEquals("/stores/boutique.json", requests.single().path)
        assertEquals("print=silent", requests.single().query)
    }

    @Test
    fun `a pull builds the realtime database query string`() = runBlocking {
        nextResponse = { it.reply(200, """{"s1":{"updatedAt":12}}""") }
        val gateway = gateway()

        val result = gateway.get(
            "stores/boutique/sales",
            RealtimeQuery(orderBy = "updatedAt", startAt = 10, limitToFirst = 500),
        )

        val request = requests.single()
        assertEquals("GET", request.method)
        assertEquals("/stores/boutique/sales.json", request.path)
        // `orderBy` doit etre un chemin encode en JSON, sinon Firebase repond 400.
        assertTrue(request.query!!.contains("orderBy=%22updatedAt%22"), request.query!!)
        assertTrue(request.query!!.contains("startAt=10"), request.query!!)
        assertTrue(request.query!!.contains("limitToFirst=500"), request.query!!)
        assertNotNull(result)
    }

    @Test
    fun `an empty database answer becomes null`() = runBlocking {
        nextResponse = { it.reply(200, "null") }
        assertNull(gateway().get("stores/boutique/sales/ghost"))
    }

    @Test
    fun `http errors surface as realtime exceptions`() {
        nextResponse = { it.reply(401, """{"error":"Permission denied"}""") }
        val failure = assertThrows<RealtimeDbException> {
            runBlocking { gateway().patch("stores/boutique", mapOf("a" to JsonPrimitive("b"))) }
        }
        assertEquals(401, failure.statusCode)
    }

    @Test
    fun `an unreachable database is reported as offline not as a crash`() {
        val unreachable = RestRealtimeDbGateway("http://127.0.0.1:1", NoAuth)
        val failure = assertThrows<OfflineException> {
            runBlocking { unreachable.get("stores/boutique/sales") }
        }
        assertTrue(failure.message!!.contains("injoignable"))
    }

    @Test
    fun `the authorization header is attached when an auth strategy is supplied`() = runBlocking {
        nextResponse = { it.reply(200, "{}") }
        val bearer = object : RealtimeDbAuth {
            override suspend fun authorizationHeader(): String = "Bearer token-123"
        }

        gateway(bearer).get("stores/boutique/meta")

        assertEquals("Bearer token-123", requests.single().authorization)
    }

    @Test
    fun `no authorization header is sent without an auth strategy`() = runBlocking {
        nextResponse = { it.reply(200, "{}") }

        gateway().get("stores/boutique/meta")

        assertNull(requests.single().authorization)
    }

    @Test
    fun `keys needing escaping are percent encoded`() {
        assertEquals("a%2Eb", RealtimePaths.escape("a.b"))
        assertEquals("a%23b%24c%5Bd%5D", RealtimePaths.escape("a#b\$c[d]"))
        assertEquals("T-ABCDEF-000001", RealtimePaths.escape("T-ABCDEF-000001"))
        assertEquals("stores/boutique/sales", RealtimePaths.sales("boutique"))
        assertEquals("stores/boutique/ticketIndex/T-ABCDEF-000001", RealtimePaths.ticketIndexEntry("boutique", "T-ABCDEF-000001"))
    }
}
