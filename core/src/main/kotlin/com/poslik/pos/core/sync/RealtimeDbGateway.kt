package com.poslik.pos.core.sync

import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class RealtimeQuery(
    val orderBy: String? = null,
    val startAt: Long? = null,
    val endAt: Long? = null,
    val limitToFirst: Long? = null,
    val limitToLast: Long? = null,
    val shallow: Boolean = false,
) {
    /**
     * Chaine de requete REST, encodee une seule fois et partagee par les deux
     * passerelles (JVM et Android).
     *
     * `orderBy` doit etre un chemin encode en JSON (%22updatedAt%22) puis percent-encode :
     * envoye brut ou avec une erreur de frappe, Firebase repond 400
     * `orderBy must be a valid JSON encoded path`. Une seule implementation, testee ici,
     * evite que les deux clients divergent.
     */
    fun toQueryString(): String {
        val parameters = buildList {
            orderBy?.let { add("orderBy" to RealtimePaths.encodeOrderBy(it)) }
            startAt?.let { add("startAt" to it.toString()) }
            endAt?.let { add("endAt" to it.toString()) }
            limitToFirst?.let { add("limitToFirst" to it.toString()) }
            limitToLast?.let { add("limitToLast" to it.toString()) }
            if (shallow) add("shallow" to "true")
        }
        if (parameters.isEmpty()) return ""
        return parameters.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
    }
}

interface RealtimeDbGateway {
    val description: String

    suspend fun patch(path: String, updates: Map<String, JsonElement>, printSilent: Boolean = false): JsonElement?

    suspend fun get(path: String, query: RealtimeQuery = RealtimeQuery()): JsonElement?
}

class RealtimeDbException(
    val statusCode: Int,
    val body: String,
    val path: String,
) : RuntimeException("realtime database $path failed with HTTP $statusCode: $body.take(400)")

class OfflineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object RealtimePaths {

    const val SERVER_VALUE_KEY = ".sv"
    const val SERVER_VALUE_TIMESTAMP = "timestamp"

    private const val ILLEGAL_KEY_CHARACTERS = ".#$[]/"
    private const val HEX = "0123456789ABCDEF"

    fun sales(storeId: String): String = "stores/${escape(storeId)}/sales"

    fun sale(storeId: String, saleId: String): String = "${sales(storeId)}/${escape(saleId)}"

    fun ticketIndex(storeId: String): String = "stores/${escape(storeId)}/ticketIndex"

    fun ticketIndexEntry(storeId: String, ticketNumber: String): String =
        "${ticketIndex(storeId)}/${escape(ticketNumber)}"

    fun terminals(storeId: String): String = "stores/${escape(storeId)}/terminals"

    fun terminal(storeId: String, terminalId: String): String =
        "${terminals(storeId)}/${escape(terminalId)}"

    fun namespaces(storeId: String): String = "stores/${escape(storeId)}/namespaces"

    fun storeRoot(storeId: String): String = "stores/${escape(storeId)}"

    fun storeMeta(storeId: String): String = "stores/${escape(storeId)}/meta"

    fun serverTimestampUpdate(): JsonObject = buildJsonObject {
        put("updatedAt", serverTimestampSentinel())
    }

    /**
     * Placeholder senteleve par le serveur Firebase pour ecrire l'horodatage du serveur.
     *
     * L'API REST n'accepte QUE `{".sv":"timestamp"}`. `ServerValue.TIMESTAMP` est un symbole
     * du SDK client, pas un format de transport : envoye tel quel, Firebase repond 400 et
     * n'ecrit rien. (Verifie en direct : 204 + valeur resolue avec `.sv`, 400 avec `ServerValue`.)
     */
    fun serverTimestampSentinel(): JsonObject = buildJsonObject {
        put(SERVER_VALUE_KEY, JsonPrimitive(SERVER_VALUE_TIMESTAMP))
    }

    fun escape(key: String): String = buildString(key.length) {
        for (character in key) {
            if (character in ILLEGAL_KEY_CHARACTERS) {
                append('%').append(HEX[(character.code shr 4) and 0xF]).append(HEX[character.code and 0xF])
            } else {
                append(character)
            }
        }
    }

    /**
     * Encode une cle de chemin pour le parametre `orderBy` de l'API REST.
     *
     * `orderBy` attend un chemin encode en JSON, donc entre guillemets : `%22updatedAt%22`.
     * Envoyee telle quelle (`orderBy=updatedAt`), Firebase repond 400 avec
     * `orderBy must be a valid JSON encoded path`.
     */
    fun encodeOrderBy(path: String): String = "\"$path\""
}
