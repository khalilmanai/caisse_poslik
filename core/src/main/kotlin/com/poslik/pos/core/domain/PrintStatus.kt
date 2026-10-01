package com.poslik.pos.core.domain

enum class PrintState {
    PENDING,
    PRINTING,
    PRINTED,
    FAILED;

    fun canTransitionTo(next: PrintState): Boolean = when (this) {
        PRINTED -> false
        PENDING -> next == PRINTING || next == FAILED
        PRINTING -> next == PRINTED || next == FAILED || next == PENDING
        FAILED -> next == PRINTING || next == PENDING
    }

    fun rank(): Int = when (this) {
        PENDING -> 0
        PRINTING -> 1
        FAILED -> 2
        PRINTED -> 3
    }
}

data class PrintStatus(
    val state: PrintState,
    val attempts: Int = 0,
    val nextAttemptAtEpochMs: Long = 0,
    val lastError: String? = null,
    val printedAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long = 0,
) {
    val label: String
        get() = when (state) {
            PrintState.PENDING -> "en attente"
            PrintState.PRINTING -> "en cours"
            PrintState.PRINTED -> "imprim\u00E9"
            PrintState.FAILED -> "\u00E9chec"
        }

    val needsWork: Boolean
        get() = state == PrintState.PENDING || state == PrintState.FAILED

    companion object {
        fun pending(now: Long): PrintStatus =
            PrintStatus(PrintState.PENDING, updatedAtEpochMs = now)

        fun merge(local: PrintStatus, remote: PrintStatus): PrintStatus {
            if (local.state == PrintState.PRINTED) return local
            if (remote.state == PrintState.PRINTED) return remote
            if (remote.updatedAtEpochMs != local.updatedAtEpochMs) {
                return if (remote.updatedAtEpochMs > local.updatedAtEpochMs) remote else local
            }
            return if (remote.state.rank() >= local.state.rank()) remote else local
        }
    }
}
