package com.poslik.pos.core.testing

import com.poslik.pos.core.app.ConnectivityMonitor
import com.poslik.pos.core.app.PosConfig
import com.poslik.pos.core.app.PosService
import com.poslik.pos.core.app.SwitchableConnectivity
import com.poslik.pos.core.data.SqliteLocalStore
import com.poslik.pos.core.domain.Cart
import com.poslik.pos.core.domain.CartSnapshot
import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.print.BackoffPolicy
import com.poslik.pos.core.print.PrintWorker
import com.poslik.pos.core.print.SimulatedPrintGateway
import com.poslik.pos.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope

class PosFixture(
    scope: CoroutineScope,
    online: Boolean = true,
    storeId: String = "boutique-centre-ville",
    printerFailFirstAttempts: Int = 0,
    printerFailEveryAttempt: Int = 0,
    verifyTicketIndex: Boolean = true,
    databasePath: String = SqliteLocalStore.MEMORY,
    startEpochMs: Long = 1_760_000_000_000,
) : AutoCloseable {
    val clock = MutableClock(startEpochMs)
    val connectivity: SwitchableConnectivity = SwitchableConnectivity(online)
    val store: SqliteLocalStore = SqliteLocalStore.open(databasePath, clock::now)
    val printer = SimulatedPrintGateway(printerFailFirstAttempts, printerFailEveryAttempt)
    val cloud = FakeRealtimeDb { clock.now() }
    val config = PosConfig(storeId = storeId, storeLabel = "Boutique Centre-ville")
    val printWorker = PrintWorker(
        store = store,
        gateway = printer,
        clock = clock::now,
        backoff = BackoffPolicy(baseDelayMs = 2_000, maxDelayMs = 60_000),
    )
    val syncEngine = SyncEngine(
        store = store,
        gateway = cloud,
        storeId = storeId,
        clock = clock::now,
        backoff = BackoffPolicy(baseDelayMs = 2_000, maxDelayMs = 60_000),
        verifyTicketIndex = verifyTicketIndex,
        isOnline = connectivity::isOnline,
    )
    val service = PosService(
        store = store,
        printWorker = printWorker,
        config = config,
        scope = scope,
        clock = clock::now,
        syncEngine = syncEngine,
        connectivity = connectivity as ConnectivityMonitor,
    )

    val terminalId: String get() = store.terminal().id
    val storeId: String get() = config.storeId

    fun cartOf(vararg lines: Pair<String, Int>): CartSnapshot {
        val cart = Cart()
        lines.forEach { (productId, quantity) -> cart.add(productId, quantity) }
        return cart.snapshot()
    }

    fun sell(
        vararg lines: Pair<String, Int>,
        method: PaymentMethod = PaymentMethod.CARD,
        cashGiven: Money? = null,
    ): Sale {
        clock.advance()
        return service.checkout(cartOf(*lines), method, cashGiven)
    }

    fun goOffline() = connectivity.set(false)

    fun goOnline() = connectivity.set(true)

    override fun close() {
        service.shutdown()
        store.close()
    }
}
