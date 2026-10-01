package com.poslik.pos.core.testing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.poslik.pos.core.sync.OfflineException
import com.poslik.pos.core.sync.RealtimeDbException
import com.poslik.pos.core.sync.RealtimeDbGateway
import com.poslik.pos.core.sync.RealtimePaths
import com.poslik.pos.core.sync.RealtimeQuery

class FakeRealtimeDb(private val serverClock: () -> Long) : RealtimeDbGateway {

    override val description: String = "fake-realtime-database"

    private var root: JsonObject = JsonObject(emptyMap())
    private val lock = Any()

    var online: Boolean = true
    var transientFailures: Int = 0
    var patches: Int = 0
    var gets: Int = 0
    var enforceTicketIndexRule: Boolean = true
    var dropTicketIndexWrites: Boolean = false
    val protectedPaths: MutableSet<String> = mutableSetOf()
    val readLog: MutableList<String> = mutableListOf()
    val writeLog: MutableList<String> = mutableListOf()

    override suspend fun patch(path: String, updates: Map<String, JsonElement>, printSilent: Boolean): JsonElement? {
        synchronized(lock) {
            if (!online) throw OfflineException("fake cloud is offline")
            if (transientFailures > 0) {
                transientFailures--
                throw RealtimeDbException(503, "simulated backend error", path)
            }
            val fullPaths = updates.keys.map { child -> "$path/$child" }
            fullPaths.firstOrNull { it in protectedPaths }?.let {
                throw RealtimeDbException(401, "PERMISSION_DENIED: $it", path)
            }
            if (enforceTicketIndexRule) {
                fullPaths.filter { it.contains("/ticketIndex/") }.forEach { full ->
                    val owner = getAt(root, segments(full)) as? JsonPrimitive
                    if (owner != null && updates[full.substringAfterLast('/')]?.let { it as? JsonPrimitive }?.content != owner.content) {
                        throw RealtimeDbException(401, "PERMISSION_DENIED: ticket index $full is immutable", path)
                    }
                }
            }
            patches++
            fullPaths.forEach { writeLog.add("PATCH $it") }
            var next = root
            for ((key, value) in updates) {
                val fullPath = "$path/$key"
                if (dropTicketIndexWrites && fullPath.contains("/ticketIndex/")) continue
                next = setAt(next, segments(path) + segments(key), resolveSentinels(value))
            }
            root = next
            return if (printSilent) null else getAt(root, segments(path))
        }
    }

    override suspend fun get(path: String, query: RealtimeQuery): JsonElement? {
        synchronized(lock) {
            gets++
            readLog.add("GET $path ${query.orderBy ?: ""} ${query.startAt ?: ""}")
            if (!online) throw OfflineException("fake cloud is offline")
            val node = getAt(root, segments(path)) ?: return null
            if (query.shallow) {
                val children = when (node) {
                    is JsonObject -> node.keys
                    is JsonArray -> node.indices.map { it.toString() }
                    else -> emptyList()
                }
                return JsonObject(children.associateWith { JsonPrimitive(true) })
            }
            val orderBy = query.orderBy ?: return node
            if (node !is JsonObject) return node
            val entries = node.mapNotNull { (key, value) ->
                val field = (value as? JsonObject)?.get(orderBy) as? JsonPrimitive
                val order = field?.longOrNull ?: return@mapNotNull null
                key to order
            }
            val filtered = entries
                .filter { (_, order) -> query.startAt == null || order >= query.startAt }
                .filter { (_, order) -> query.endAt == null || order <= query.endAt }
                .sortedBy { (_, order) -> order }
                .map { (key, _) -> key to node.getValue(key) }
            val limited = query.limitToFirst?.toInt()?.let { filtered.take(it) }
                ?: query.limitToLast?.toInt()?.let { filtered.takeLast(it) }
                ?: filtered
            return JsonObject(limited.toMap())
        }
    }

    fun protect(path: String) = synchronized(lock) { protectedPaths.add(path) }

    fun unprotect(path: String) = synchronized(lock) { protectedPaths.remove(path) }

    fun saleIds(storeId: String): List<String> = synchronized(lock) {
        (getAt(root, listOf("stores", storeId, "sales")) as? JsonObject)?.keys?.toList().orEmpty()
    }

    fun saleCount(storeId: String): Int = saleIds(storeId).size

    fun ticketOwner(storeId: String, ticketNumber: String): String? = synchronized(lock) {
        (getAt(root, listOf("stores", storeId, "ticketIndex", ticketNumber)) as? JsonPrimitive)?.content
    }

    fun namespaceOwner(storeId: String, namespace: String): String? = synchronized(lock) {
        val node = getAt(root, listOf("stores", storeId, "namespaces", namespace)) as? JsonObject
        (node?.get("terminalId") as? JsonPrimitive)?.content
    }

    fun terminalDocument(storeId: String, terminalId: String): JsonObject? = synchronized(lock) {
        getAt(root, listOf("stores", storeId, "terminals", terminalId)) as? JsonObject
    }

    fun snapshot(): JsonObject = synchronized(lock) { root }

    fun seed(storeId: String, json: JsonObject) = synchronized(lock) {
        root = setAt(root, listOf("stores", storeId), json)
    }

    private fun resolveSentinels(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> {
            // Reproduit l'API REST : le serveur ne resout QUE le placeholder {".sv":"timestamp"}.
            // Tout autre forme (ex. l'ancien {".":"ServerValue.TIMESTAMP"}) est stockee telle quelle.
            val serverValue = value[RealtimePaths.SERVER_VALUE_KEY] as? JsonPrimitive
            if (value.size == 1 && serverValue?.content == RealtimePaths.SERVER_VALUE_TIMESTAMP) {
                JsonPrimitive(serverClock())
            } else {
                JsonObject(value.mapValues { resolveSentinels(it.value) })
            }
        }

        is JsonArray -> JsonArray(value.map { resolveSentinels(it) })
        else -> value
    }

    private fun segments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    private fun getAt(node: JsonElement, path: List<String>): JsonElement? {
        var current: JsonElement? = node
        for (segment in path) {
            current = when (val active = current) {
                is JsonObject -> active[segment] ?: return null
                else -> return null
            }
        }
        return current?.takeUnless { it is JsonNull }
    }

    private fun setAt(node: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        if (path.isEmpty()) return value as? JsonObject ?: JsonObject(emptyMap())
        val (head, tail) = path.first() to path.drop(1)
        val child = node[head] as? JsonObject ?: JsonObject(emptyMap())
        val updated = if (tail.isEmpty()) value else setAt(child, tail, value)
        return JsonObject(node + (head to updated))
    }
}

class MutableClock(private var current: Long) {

    fun now(): Long = current

    fun advance(millis: Long = 1): Long {
        current += millis
        return current
    }

    fun set(value: Long) {
        current = value
    }
}
