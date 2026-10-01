package com.poslik.pos.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class RestRealtimeDbGateway(
    private val databaseUrl: String,
    private val auth: RealtimeDbAuth = NoAuth,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RealtimeDbGateway {

    override val description: String = "REST $databaseUrl"

    override suspend fun patch(
        path: String,
        updates: Map<String, JsonElement>,
        printSilent: Boolean,
    ): JsonElement? {
        val body = buildJsonObject {
            updates.forEach { (key, value) -> put(key, value) }
        }
        return request("PATCH", path, body.toString(), if (printSilent) "print=silent" else "")
    }

    override suspend fun get(path: String, query: RealtimeQuery): JsonElement? =
        request("GET", path, null, query.toQueryString())

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        queryString: String = "",
    ): JsonElement? = withContext(Dispatchers.IO) {
        val builder = StringBuilder(trimTrailingSlash(databaseUrl))
            .append('/')
            .append(path)
            .append(".json")
        if (queryString.isNotEmpty()) builder.append('?').append(queryString)
        val requestBuilder = HttpRequest.newBuilder(URI.create(builder.toString()))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Accept", "application/json")
        val authorization = auth.authorizationHeader()
        if (authorization != null) requestBuilder.header("Authorization", authorization)
        if (body != null) {
            requestBuilder.header("Content-Type", "application/json; charset=utf-8")
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        val response = try {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        } catch (error: IOException) {
            throw OfflineException("base de donn\u00E9es injoignable : ${error.message}", error)
        }
        val text = response.body().trim()
        if (response.statusCode() !in 200..299) {
            throw RealtimeDbException(response.statusCode(), text, path)
        }
        if (text.isEmpty() || text == "null") return@withContext null
        runCatching { json.parseToJsonElement(text) }.getOrElse {
            throw RealtimeDbException(response.statusCode(), text, path)
        }
    }

    private fun trimTrailingSlash(value: String): String = value.trimEnd('/')

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 20L

        fun defaultHttpClient(): HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
