package com.poslik.pos.pos.app

import com.poslik.pos.core.app.PosConfig
import com.poslik.pos.core.app.PosService
import com.poslik.pos.core.app.SwitchableConnectivity
import com.poslik.pos.core.data.LocalStore
import com.poslik.pos.core.data.SqliteLocalStore
import com.poslik.pos.core.domain.Cart
import com.poslik.pos.core.domain.HistoryEntry
import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.print.PrintGateway
import com.poslik.pos.core.print.PrintSpoolGateway
import com.poslik.pos.core.print.PrintWorker
import com.poslik.pos.core.print.SimulatedPrintGateway
import com.poslik.pos.core.sync.RealtimeDbGateway
import com.poslik.pos.core.sync.RestRealtimeDbGateway
import com.poslik.pos.core.sync.SyncEngine
import com.poslik.pos.core.util.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

data class AppOptions(
    val storeId: String,
    val storeLabel: String,
    val databasePath: String,
    val spoolDirectory: Path,
    val firebaseUrl: String?,
    val printerFailureEvery: Int,
    val startOffline: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): AppOptions {
            val values = mutableMapOf<String, String>()
            var index = 0
            while (index < args.size) {
                val token = args[index]
                require(token.startsWith("--")) { "option inconnue : $token" }
                if (index + 1 < args.size && !args[index + 1].startsWith("--")) {
                    values[token.removePrefix("--")] = args[index + 1]
                    index += 2
                } else {
                    values[token.removePrefix("--")] = "true"
                    index++
                }
            }
            val home = System.getProperty("user.home") ?: "."
            return AppOptions(
                storeId = values["store"] ?: "boutique-centre-ville",
                storeLabel = values["label"] ?: "Boutique Centre-ville",
                databasePath = values["db"] ?: Paths.get(home, ".caisse-poslik", "caisse.db").toString(),
                spoolDirectory = Paths.get(values["spool"] ?: Paths.get(home, ".caisse-poslik", "tickets").toString()),
                firebaseUrl = values["firebase-url"] ?: System.getenv("FIREBASE_DATABASE_URL"),
                printerFailureEvery = values["printer-fail-every"]?.toInt() ?: 0,
                startOffline = values["offline"] == "true" || System.getenv("POS_FORCE_OFFLINE") == "1",
            )
        }
    }
}

object ApplicationFactory {

    fun build(options: AppOptions): PosApplication {
        val store: LocalStore = SqliteLocalStore.open(options.databasePath)
        val printer: PrintGateway = if (options.printerFailureEvery > 0) {
            SimulatedPrintGateway(failEveryAttempt = options.printerFailureEvery)
        } else {
            PrintSpoolGateway(options.spoolDirectory, options.storeLabel)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val printWorker = PrintWorker(store, printer)
        val connectivity = SwitchableConnectivity(!options.startOffline)

        val gateway: RealtimeDbGateway? = options.firebaseUrl
            ?.takeIf { it.startsWith("http") }
            ?.let { RestRealtimeDbGateway(it) }
        val syncEngine = gateway?.let {
            SyncEngine(
                store = store,
                gateway = it,
                storeId = options.storeId,
                isOnline = connectivity::isOnline,
            )
        }
        val service = PosService(
            store = store,
            printWorker = printWorker,
            config = PosConfig(storeId = options.storeId, storeLabel = options.storeLabel),
            scope = scope,
            syncEngine = syncEngine,
            connectivity = connectivity,
        )
        return PosApplication(service, store, printWorker, syncEngine, scope, options, connectivity)
    }
}

class PosApplication(
    val service: PosService,
    val store: LocalStore,
    val printWorker: PrintWorker,
    val syncEngine: SyncEngine?,
    private val scope: CoroutineScope,
    val options: AppOptions,
    private val connectivity: SwitchableConnectivity,
) : AutoCloseable {

    val cart = Cart()

    fun checkout(method: PaymentMethod, cashGiven: Money?): Sale {
        val sale = service.checkout(cart.snapshot(), method, cashGiven)
        cart.clear()
        return sale
    }

    fun isOnline(): Boolean = connectivity.isOnline()

    fun setOnline(online: Boolean) = connectivity.set(online)

    fun synchroniseNow() = runBlocking { syncEngine?.syncOnce() }

    fun startup() = runBlocking { service.onStartup() }

    override fun close() {
        service.shutdown()
        scope.cancel()
        store.close()
    }
}

object ConsoleFormatter {
    fun money(amount: Money): String = amount.formatPlain()

    fun history(rows: List<HistoryEntry>): String = buildString {
        appendLine(String.format("%-20s %12s  %-10s %-12s %s", "TICKET", "MONTANT", "IMPRESSION", "SYNCHRO", "DATE"))
        appendLine("-".repeat(78))
        rows.forEach { row ->
            appendLine(
                String.format(
                    "%-20s %12s  %-10s %-12s %s",
                    row.ticketNumber,
                    money(row.total),
                    row.printStatusLabel,
                    row.syncStatusLabel,
                    TimeFormat.history(row.createdAtEpochMs),
                ),
            )
        }
    }

    fun banner(options: AppOptions, terminalId: String, online: Boolean, syncConfigured: Boolean): String = buildString {
        appendLine("=".repeat(78))
        appendLine("  ${options.storeLabel.uppercase()} - CAISSE (terminal $terminalId)")
        appendLine("  r\u00E9seau : ${if (online) "EN LIGNE" else "HORS LIGNE"}")
        appendLine(
            "  firebase : " + if (syncConfigured) options.firebaseUrl
            else "NON CONFIGUR\u00C9 (les ventes restent locales, aucune perte)",
        )
        appendLine("  base locale : ${options.databasePath}")
        appendLine("  tickets     : ${options.spoolDirectory}")
        appendLine("=".repeat(78))
    }
}