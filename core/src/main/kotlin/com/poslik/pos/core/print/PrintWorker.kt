package com.poslik.pos.core.print

import com.poslik.pos.core.data.LocalStore
import com.poslik.pos.core.domain.PrintState
import com.poslik.pos.core.domain.PrintStatus
import com.poslik.pos.core.domain.SaleRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BackoffPolicy(
    private val baseDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 5 * 60_000,
) {
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return baseDelayMs
        val exponent = (attempt - 1).coerceAtMost(32)
        val multiplier = 1L shl exponent
        val delay = if (baseDelayMs > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else baseDelayMs * multiplier
        return delay.coerceAtMost(maxDelayMs)
    }
}

data class PrintPumpReport(
    val attempted: Int,
    val printed: Int,
    val failed: Int,
)

class PrintWorker(
    private val store: LocalStore,
    private val gateway: PrintGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val batchSize: Int = 20,
) {

    private val mutex = Mutex()

    suspend fun recoverAtStartup(): Int = store.recoverPrintQueueAtStartup()

    suspend fun pump(): PrintPumpReport = mutex.withLock {
        val now = clock()
        val jobs = store.printQueue(now, batchSize)
        var printed = 0
        var failed = 0
        jobs.forEach { record ->
            if (printOne(record)) printed++ else failed++
        }
        PrintPumpReport(attempted = jobs.size, printed = printed, failed = failed)
    }

    suspend fun pumpUntilEmpty(maxRounds: Int = 10): PrintPumpReport {
        var attempted = 0
        var printed = 0
        var failed = 0
        repeat(maxRounds) {
            val report = pump()
            attempted += report.attempted
            printed += report.printed
            failed += report.failed
            if (report.attempted == 0) return PrintPumpReport(attempted, printed, failed)
        }
        return PrintPumpReport(attempted, printed, failed)
    }

    private suspend fun printOne(record: SaleRecord): Boolean {
        val startedAt = clock()
        val attemptNumber = record.print.attempts + 1
        val claimed = store.transaction {
            val fresh = store.byId(record.sale.id)
            if (fresh == null || !fresh.print.needsWork) return@transaction false
            store.updatePrint(
                record.sale.id,
                PrintStatus(
                    state = PrintState.PRINTING,
                    attempts = record.print.attempts,
                    nextAttemptAtEpochMs = 0,
                    lastError = record.print.lastError,
                    printedAtEpochMs = null,
                    updatedAtEpochMs = startedAt,
                ),
            )
            true
        }
        if (!claimed) return false

        return try {
            gateway.print(record.sale, attemptNumber)
            val doneAt = clock()
            store.updatePrint(
                record.sale.id,
                PrintStatus(
                    state = PrintState.PRINTED,
                    attempts = attemptNumber,
                    nextAttemptAtEpochMs = 0,
                    lastError = null,
                    printedAtEpochMs = doneAt,
                    updatedAtEpochMs = doneAt,
                ),
            )
            true
        } catch (failure: PrintFailure) {
            val failedAt = clock()
            store.updatePrint(
                record.sale.id,
                PrintStatus(
                    state = PrintState.FAILED,
                    attempts = attemptNumber,
                    nextAttemptAtEpochMs = failedAt + backoff.delayFor(attemptNumber),
                    lastError = failure.message,
                    printedAtEpochMs = null,
                    updatedAtEpochMs = failedAt,
                ),
            )
            false
        }
    }
}
