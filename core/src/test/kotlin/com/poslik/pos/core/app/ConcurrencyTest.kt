package com.poslik.pos.core.app

import com.poslik.pos.core.data.SqliteLocalStore
import com.poslik.pos.core.domain.Cart
import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.domain.TerminalNamespace
import com.poslik.pos.core.domain.TicketNumber
import com.poslik.pos.core.print.PrintSpoolGateway
import com.poslik.pos.core.print.PrintWorker
import com.poslik.pos.core.print.SimulatedPrintGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyTest {

    @Test
    fun `parallel cashouts never produce a duplicated ticket number`(@TempDir directory: Path) {
        val database = directory.resolve("caisse.db").toString()
        val store = SqliteLocalStore.open(database)
        val scope = CoroutineScope(Dispatchers.IO)
        val service = PosService(
            store = store,
            printWorker = PrintWorker(store, PrintSpoolGateway(directory.resolve("spool"))),
            config = PosConfig(storeId = "boutique"),
            scope = scope,
        )

        val threads = 8
        val perThread = 40
        val total = threads * perThread
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = AtomicInteger()

        repeat(threads) {
            pool.submit {
                val cart = Cart()
                cart.add("ESP", 1)
                val snapshot = cart.snapshot()
                start.await()
                repeat(perThread) {
                    runCatching { service.checkout(snapshot, PaymentMethod.CARD, null) }
                        .onFailure { failures.incrementAndGet() }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(120, TimeUnit.SECONDS), "all threads must finish")
        pool.shutdown()

        assertEquals(0, failures.get(), "no checkout may fail")
        val stored = store.history(total + 10)
        assertEquals(total, stored.size, "every sale must be persisted exactly once")

        val numbers = stored.map { it.sale.ticketNumber.value }
        assertEquals(total, numbers.toSet().size, "ticket numbers must be unique")

        val sequences = numbers.map { TicketNumber.sequenceOf(it) }.sorted()
        assertEquals((1L..total.toLong()).toList(), sequences, "the sequence must stay dense with no gap")
        assertEquals(total.toLong(), store.terminal().lastSequence)
        store.close()
    }

    @Test
    fun `a refused checkout never burns a ticket number`() {
        val store = SqliteLocalStore.open(SqliteLocalStore.MEMORY)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val service = PosService(
            store = store,
            printWorker = PrintWorker(store, SimulatedPrintGateway()),
            config = PosConfig(storeId = "boutique"),
            scope = scope,
        )
        val empty = Cart().snapshot()

        repeat(3) {
            runCatching { service.checkout(empty, PaymentMethod.CARD, null) }
        }
        assertEquals(0L, store.terminal().lastSequence)

        val cart = Cart()
        cart.add("ESP", 1)
        val sale: Sale = service.checkout(cart.snapshot(), PaymentMethod.CARD, Money.ofMajor(1))
        val terminal = store.terminal()
        val expectedPrefix = TerminalNamespace(terminal.id, terminal.namespaceLength).short

        assertEquals("T-$expectedPrefix-000001", sale.ticketNumber.value)
        store.close()
    }
}