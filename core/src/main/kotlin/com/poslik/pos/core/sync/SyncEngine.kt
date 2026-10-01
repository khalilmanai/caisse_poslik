package com.poslik.pos.core.sync

import com.poslik.pos.core.data.LocalStore
import com.poslik.pos.core.data.SaleJson
import com.poslik.pos.core.domain.PrintStatus
import com.poslik.pos.core.domain.SaleRecord
import com.poslik.pos.core.domain.SyncState
import com.poslik.pos.core.domain.SyncStatus
import com.poslik.pos.core.domain.TerminalNamespace
import com.poslik.pos.core.print.BackoffPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SyncReport(
    val offline: Boolean = false,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val failed: Int = 0,
    val conflicts: Int = 0,
    val namespaceLength: Int? = null,
) {
    val didWork: Boolean get() = pushed > 0 || pulled > 0
}

data class PushOutcome(
    val pushed: Int = 0,
    val failed: Int = 0,
    val conflicts: Int = 0,
)

class SyncEngine(
    private val store: LocalStore,
    private val gateway: RealtimeDbGateway,
    private val storeId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: BackoffPolicy = BackoffPolicy(baseDelayMs = 5_000, maxDelayMs = 300_000),
    private val batchSize: Int = 25,
    private val verifyTicketIndex: Boolean = true,
    private val isOnline: () -> Boolean = { true },
) {

    private val mutex = Mutex()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun start(scope: CoroutineScope) {
        scope.launch {
            for (ignored in wakeups) {
                runCatching { syncOnce() }
            }
        }
    }

    fun requestSync() {
        wakeups.trySend(Unit)
    }

    suspend fun syncOnce(): SyncReport = mutex.withLock {
        if (!isOnline()) return@withLock SyncReport(offline = true)
        var report = SyncReport()
        try {
            report = report.copy(namespaceLength = reconcileTerminal())
        } catch (offline: OfflineException) {
            return@withLock report.copy(offline = true)
        } catch (denied: RealtimeDbException) {
            return@withLock report.copy(failed = report.failed + 1)
        }
        val push = try {
            pushPending()
        } catch (offline: OfflineException) {
            return@withLock report.copy(offline = true, failed = report.failed + store.unsyncedCount())
        }
        report = report.copy(pushed = push.pushed, failed = push.failed, conflicts = push.conflicts)
        val pulled = try {
            pullChanges()
        } catch (offline: OfflineException) {
            return@withLock report.copy(offline = true)
        } catch (denied: RealtimeDbException) {
            // Un refus serveur (4xx/5xx) sur le pull ne doit jamais se propager a
            // l'appelant : la file locale reste intacte et le tirage reprend au prochain tour.
            return@withLock report.copy(failed = report.failed + 1)
        }
        return@withLock report.copy(pulled = pulled)
    }

    suspend fun reconcileTerminal(): Int? {
        val state = store.terminal()
        var length = state.namespaceLength
        var claimed = tryClaim(TerminalNamespace(state.id, length))
        while (!claimed && length < TerminalNamespace.MAX_LENGTH) {
            length = (length + 2).coerceAtMost(TerminalNamespace.MAX_LENGTH)
            claimed = tryClaim(TerminalNamespace(state.id, length))
        }
        if (length != state.namespaceLength) store.setNamespaceLength(length)

        val remote = gateway.get(RealtimePaths.terminal(storeId, state.id)) as? JsonObject
        val remoteSequence = remote?.longValue("lastTicketSequence") ?: 0L
        store.raiseSequenceTo(remoteSequence)

        val now = clock()
        val terminalDocument = buildJsonObject {
            put("terminalId", state.id)
            put("namespace", TerminalNamespace(state.id, length).short)
            put("lastTicketSequence", store.peekSequence())
            put("lastSeenAt", RealtimePaths.serverTimestampSentinel())
        }
        gateway.patch(
            RealtimePaths.terminals(storeId),
            mapOf(RealtimePaths.escape(state.id) to terminalDocument),
            printSilent = true,
        )
        store.markTerminalClaimed(now)
        return length
    }

    private suspend fun tryClaim(namespace: TerminalNamespace): Boolean = try {
        gateway.patch(
            RealtimePaths.namespaces(storeId),
            mapOf(
                RealtimePaths.escape(namespace.short) to buildJsonObject {
                    put("terminalId", namespace.terminalId)
                    put("claimedAt", RealtimePaths.serverTimestampSentinel())
                },
            ),
            printSilent = true,
        )
        true
    } catch (denied: RealtimeDbException) {
        if (denied.statusCode == 401 || denied.statusCode == 403) false else throw denied
    }

    suspend fun pushPending(): PushOutcome {
        val now = clock()
        val batch = store.syncQueue(now, batchSize)
        var pushed = 0
        var failed = 0
        var conflicts = 0
        for (record in batch) {
            val saleObject = salePayload(record, RealtimePaths.serverTimestampSentinel())
            val updates = linkedMapOf(
                "sales/${RealtimePaths.escape(record.sale.id)}" to saleObject,
                "ticketIndex/${RealtimePaths.escape(record.sale.ticketNumber.value)}" to JsonPrimitive(record.sale.id),
            )
            val conflictNote = try {
                gateway.patch(RealtimePaths.storeRoot(storeId), updates, printSilent = true)
                if (verifyTicketIndex) verifyTicketOwner(record.sale.ticketNumber.value, record.sale.id) else null
            } catch (offline: OfflineException) {
                scheduleRetry(record.sale.id, record.sync.attempts, offline.message, SyncState.PENDING, OFFLINE_RETRY_MS)
                failed++
                break
            } catch (error: RealtimeDbException) {
                scheduleRetry(
                    record.sale.id,
                    record.sync.attempts,
                    error.message,
                    SyncState.FAILED,
                    backoff.delayFor(record.sync.attempts + 1),
                )
                failed++
                continue
            }
            if (conflictNote == null) {
                markSynced(record.sale.id, now)
                pushed++
            } else {
                markConflict(record.sale.id, conflictNote)
                conflicts++
            }
        }
        return PushOutcome(pushed = pushed, failed = failed, conflicts = conflicts)
    }

    private suspend fun verifyTicketOwner(ticketNumber: String, saleId: String): String? {
        val node = gateway.get(RealtimePaths.ticketIndexEntry(storeId, ticketNumber)) as? JsonPrimitive
        val owner = node?.content
        if (owner == saleId) return null
        return "ticket $ticketNumber appartient d\u00E9j\u00E0 \u00E0 la vente ${owner ?: "?"}"
    }

    private fun salePayload(record: SaleRecord, updatedAt: JsonObject): JsonObject {
        val base = SaleJson.toJson(record, serverUpdatedAtEpochMs = 0)
        return buildJsonObject {
            base.forEach { (key, value) ->
                if (key == "updatedAt") put(key, updatedAt) else put(key, value)
            }
        }
    }

    suspend fun pullChanges(): Int {
        val cursor = store.syncCursor()
        val node = pullSalesNode(cursor) ?: return 0

        val now = clock()
        var pulled = 0
        var highest = cursor
        for ((saleId, value) in node) {
            val remoteObject = value as? JsonObject ?: continue
            val remote = SaleJson.fromJson(remoteObject) ?: continue
            val serverUpdatedAt = remote.sync.serverUpdatedAtEpochMs ?: 0L
            val existing = store.byId(saleId)
            if (existing == null) {
                try {
                    store.insert(remote)
                    pulled++
                } catch (constraint: java.sql.SQLException) {
                    continue
                }
            } else {
                val mergedPrint = PrintStatus.merge(existing.print, remote.print)
                if (mergedPrint != existing.print) store.updatePrint(saleId, mergedPrint)
                store.setServerUpdatedAt(saleId, serverUpdatedAt)
                if (existing.sync.state != SyncState.CONFLICT && existing.sync.state != SyncState.SYNCED) {
                    store.updateSync(
                        saleId,
                        SyncStatus(
                            state = SyncState.SYNCED,
                            attempts = existing.sync.attempts,
                            nextAttemptAtEpochMs = 0,
                            lastError = null,
                            syncedAtEpochMs = now,
                            serverUpdatedAtEpochMs = serverUpdatedAt,
                        ),
                    )
                    pulled++
                }
            }
            if (remote.sale.terminalId == store.terminal().id) {
                store.raiseSequenceTo(remote.sale.ticketNumber.sequence)
            }
            if (serverUpdatedAt > highest) highest = serverUpdatedAt
        }
        if (highest > cursor) store.setSyncCursor(highest)
        return pulled
    }

    /**
     * Le tirage ordonne les ventes par `updatedAt`, ce qui exige un `.indexOn` cote serveur.
     * Sur une instance dont les regles deployees ne l'port pas, Firebase refuse la requete
     * avec un 400 (`Index not defined`). On retombe alors sur le noeud entier : les ventes
     * sont deja dedupliquees par id dans [pullChanges], seul le volume retelecharge augmente,
     * et aucun tirage n'est perdu pour autant. Le refus est memorise pour ne pas retester
     * la requete refusee a chaque synchronisation.
     */
    private var orderByUnsupported = false

    private suspend fun pullSalesNode(cursor: Long): JsonObject? {
        val path = RealtimePaths.sales(storeId)
        if (!orderByUnsupported) {
            try {
                return gateway.get(path, RealtimeQuery(orderBy = "updatedAt", startAt = cursor, limitToFirst = PULL_LIMIT)) as? JsonObject
            } catch (denied: RealtimeDbException) {
                if (denied.statusCode != 400) throw denied
                orderByUnsupported = true
            }
        }
        return gateway.get(path) as? JsonObject
    }

    private fun markSynced(saleId: String, now: Long) {
        store.updateSync(
            saleId,
            SyncStatus(
                state = SyncState.SYNCED,
                attempts = 0,
                nextAttemptAtEpochMs = 0,
                lastError = null,
                syncedAtEpochMs = now,
                serverUpdatedAtEpochMs = null,
            ),
        )
    }

    private fun markConflict(saleId: String, note: String) {
        val existing = store.byId(saleId) ?: return
        store.updateSync(
            saleId,
            existing.sync.copy(state = SyncState.CONFLICT, conflictNote = note, nextAttemptAtEpochMs = Long.MAX_VALUE),
        )
    }

    private fun scheduleRetry(saleId: String, attempts: Int, message: String?, state: SyncState, delayMs: Long) {
        val existing = store.byId(saleId) ?: return
        val now = clock()
        store.updateSync(
            saleId,
            existing.sync.copy(
                state = state,
                attempts = attempts + 1,
                nextAttemptAtEpochMs = now + delayMs,
                lastError = message,
            ),
        )
    }

    private fun JsonObject.longValue(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    companion object {
        private const val PULL_LIMIT = 500L
        private const val OFFLINE_RETRY_MS = 5_000L
    }
}
