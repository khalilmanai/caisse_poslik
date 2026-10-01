package com.poslik.pos.core.app

import com.poslik.pos.core.data.LocalStore
import com.poslik.pos.core.domain.CartSnapshot
import com.poslik.pos.core.domain.HistoryEntry
import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.PrintStatus
import com.poslik.pos.core.domain.PushId
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.domain.SaleRecord
import com.poslik.pos.core.domain.SyncStatus
import com.poslik.pos.core.domain.TerminalNamespace
import com.poslik.pos.core.domain.TicketNumber
import com.poslik.pos.core.print.PrintPumpReport
import com.poslik.pos.core.print.PrintWorker
import com.poslik.pos.core.sync.SyncEngine
import com.poslik.pos.core.sync.SyncReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PosConfig(
    val storeId: String,
    val storeLabel: String = "Boutique",
    val currency: String = "TND",
)

data class StartupReport(
    val printQueueRecovered: Int,
    val print: PrintPumpReport,
    val sync: SyncReport?,
)

class PosService(
    val store: LocalStore,
    val printWorker: PrintWorker,
    val config: PosConfig,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val syncEngine: SyncEngine? = null,
    private val connectivity: ConnectivityMonitor = AlwaysOnline,
) {

    private var connectivitySubscription: AutoCloseable? = null
    private var backgroundJob: kotlinx.coroutines.Job? = null

    val terminalId: String get() = store.terminal().id

    fun checkout(
        snapshot: CartSnapshot,
        paymentMethod: PaymentMethod,
        cashGiven: Money? = null,
    ): Sale {
        val now = clock()
        val record = store.transaction {
            val terminal = store.terminal()
            val namespace = TerminalNamespace(terminal.id, terminal.namespaceLength)
            val sequence = store.nextSequence()
            val ticketNumber = TicketNumber.issue(namespace, sequence)
            val sale = Sale.fromCart(
                id = PushId.generate(now),
                ticketNumber = ticketNumber,
                storeId = config.storeId,
                terminalId = terminal.id,
                snapshot = snapshot,
                createdAtEpochMs = now,
                paymentMethod = paymentMethod,
                cashGiven = cashGiven,
            )
            val fresh = SaleRecord(
                sale = sale,
                print = PrintStatus.pending(now),
                sync = SyncStatus.localOnly(now),
            )
            store.insert(fresh)
            fresh
        }
        wakeWorkers()
        return record.sale
    }

    fun history(limit: Int = 50): List<HistoryEntry> = store.historyEntries(limit)

    fun saleByTicketNumber(ticketNumber: String) = store.byTicketNumber(ticketNumber)

    suspend fun onStartup(pumpPrintQueue: Boolean = true): StartupReport {
        val recovered = printWorker.recoverAtStartup()
        val print = if (pumpPrintQueue) printWorker.pumpUntilEmpty() else PrintPumpReport(0, 0, 0)
        syncEngine?.start(scope)
        syncEngine?.requestSync()
        return StartupReport(recovered, print, null)
    }

    fun startBackgroundLoops(intervalMs: Long = 2_000) {
        if (intervalMs <= 0) return
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { printWorker.pump() }
                syncEngine?.requestSync()
            }
        }
        connectivitySubscription?.close()
        connectivitySubscription = connectivity.onChange { online ->
            if (online) {
                syncEngine?.requestSync()
                scope.launch { runCatching { printWorker.pump() } }
            }
        }
    }

    fun wakeWorkers() {
        scope.launch { runCatching { printWorker.pump() } }
        syncEngine?.requestSync()
    }

    suspend fun drainPrintQueue(): PrintPumpReport = printWorker.pumpUntilEmpty()

    suspend fun syncNow(): SyncReport? = syncEngine?.syncOnce()

    fun shutdown() {
        connectivitySubscription?.close()
        backgroundJob?.cancel()
    }
}
