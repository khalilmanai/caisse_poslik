package com.poslik.pos.core.domain

enum class SyncState {
    LOCAL_ONLY,
    PENDING,
    SYNCED,
    FAILED,
    CONFLICT;

    val label: String
        get() = when (this) {
            LOCAL_ONLY -> "local"
            PENDING -> "en attente"
            SYNCED -> "synchronis\u00E9"
            FAILED -> "\u00E9chec"
            CONFLICT -> "conflit"
        }
}

data class SyncStatus(
    val state: SyncState,
    val attempts: Int = 0,
    val nextAttemptAtEpochMs: Long = 0,
    val lastError: String? = null,
    val syncedAtEpochMs: Long? = null,
    val serverUpdatedAtEpochMs: Long? = null,
    val conflictNote: String? = null,
) {
    val needsUpload: Boolean
        get() = state != SyncState.SYNCED

    companion object {
        fun localOnly(now: Long): SyncStatus = SyncStatus(SyncState.LOCAL_ONLY, nextAttemptAtEpochMs = now)

        fun synced(now: Long, serverUpdatedAt: Long): SyncStatus = SyncStatus(
            state = SyncState.SYNCED,
            nextAttemptAtEpochMs = 0,
            syncedAtEpochMs = now,
            serverUpdatedAtEpochMs = serverUpdatedAt,
        )

        fun merge(local: SyncStatus, remote: SyncStatus): SyncStatus =
            if (local.state == SyncState.CONFLICT) local else remote
    }
}

data class SaleRecord(
    val sale: Sale,
    val print: PrintStatus,
    val sync: SyncStatus,
)

data class HistoryEntry(
    val ticketNumber: String,
    val total: Money,
    val printState: PrintState,
    val printStatusLabel: String,
    val syncState: SyncState,
    val syncStatusLabel: String,
    val createdAtEpochMs: Long,
    val itemCount: Int,
    val attempts: Int,
    val lastError: String?,
    val conflictNote: String?,
) {
    companion object {
        fun from(record: SaleRecord): HistoryEntry = HistoryEntry(
            ticketNumber = record.sale.ticketNumber.value,
            total = record.sale.total,
            printState = record.print.state,
            printStatusLabel = record.print.label,
            syncState = record.sync.state,
            syncStatusLabel = record.sync.state.label,
            createdAtEpochMs = record.sale.createdAtEpochMs,
            itemCount = record.sale.itemCount,
            attempts = record.print.attempts,
            lastError = record.print.lastError,
            conflictNote = record.sync.conflictNote,
        )
    }
}
