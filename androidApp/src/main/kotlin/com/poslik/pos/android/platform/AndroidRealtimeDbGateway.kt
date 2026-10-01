package com.poslik.pos.android.platform

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import com.poslik.pos.core.sync.NoAuth
import com.poslik.pos.core.sync.OfflineException
import com.poslik.pos.core.sync.RealtimeDbAuth
import com.poslik.pos.core.sync.RealtimeDbException
import com.poslik.pos.core.sync.RealtimeDbGateway
import com.poslik.pos.core.sync.RealtimePaths
import com.poslik.pos.core.sync.RealtimeQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client HTTP pour Android, via [HttpURLConnection].
 *
 * `java.net.http.HttpClient` n'existe pas sur Android, alors que
 * `RestRealtimeDbGateway` l'utilise cote JVM. Cette variante alimente le meme
 * `SyncEngine` et le meme schema : la logique de synchronisation reste unique.
 */
class AndroidRealtimeDbGateway(
    private val databaseUrl: String,
    private val auth: RealtimeDbAuth = NoAuth,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : RealtimeDbGateway {

    override val description: String = "android-rest $databaseUrl"

    override suspend fun patch(
        path: String,
        updates: Map<String, JsonElement>,
        printSilent: Boolean,
    ): JsonElement? {
        val body = buildString {
            append('{')
            var first = true
            updates.forEach { (key, value) ->
                if (!first) append(',')
                first = false
                append(quote(key)).append(':').append(value.toString())
            }
            append('}')
        }
        val query = if (printSilent) "?print=silent" else ""
        return send("PATCH", path, query, body)
    }

    override suspend fun get(path: String, query: RealtimeQuery): JsonElement? {
        val encoded = query.toQueryString()
        return send("GET", path, if (encoded.isEmpty()) "" else "?$encoded", null)
    }

    /**
     * Une seule requete HTTP. Le chemin et la chaine de requete restent separes jusqu'ici,
     * car l'extension `.json` doit etre placee AVANT le `?` : la mettre apres produit
     * `/stores/x?print=silent.json`, que Firebase refuse (405).
     */
    private suspend fun send(
        method: String,
        path: String,
        queryString: String,
        body: String?,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val url = URL(trimTrailingSlash(databaseUrl) + "/" + path + ".json" + queryString)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
        }
        try {
            val header = auth.authorizationHeader()
            if (header != null) connection.setRequestProperty("Authorization", header)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            connection.connect()
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.let { stream -> BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() } }
                .orEmpty()
            android.util.Log.i("PosLik", "$method $url -> $status (${text.length} bytes)")
            if (status !in 200..299) {
                android.util.Log.w("PosLik", "body: ${text.take(200)}")
                throw RealtimeDbException(status, text, path)
            }
            if (text.isBlank() || text.trim() == "null") return@withContext null
            Json.parseToJsonElement(text)
        } catch (error: IOException) {
            throw OfflineException("base de donn\u00E9es injoignable : ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun trimTrailingSlash(value: String): String = value.trimEnd('/')
}