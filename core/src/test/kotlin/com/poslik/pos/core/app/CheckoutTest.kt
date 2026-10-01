package com.poslik.pos.core.app

import com.poslik.pos.core.data.SqliteLocalStore
import com.poslik.pos.core.domain.Cart
import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.PrintState
import com.poslik.pos.core.domain.ProductCatalog
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.domain.SyncState
import com.poslik.pos.core.domain.TerminalNamespace
import com.poslik.pos.core.domain.TicketNumber
import com.poslik.pos.core.testing.PosFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutTest {

    @Test
    fun `one gesture saves the sale durably then returns immediately`() = runTest {
        val fixture = PosFixture(scope = this)
        fixture.goOffline()

        val sale = fixture.sell("ESP" to 2, "CRO" to 1)

        assertEquals(Money.ofMajor(5, 600), sale.total)
        val stored = fixture.store.byId(sale.id)
        assertNotNull(stored, "the sale must already be committed when checkout returns")
        assertEquals(PrintState.PENDING, stored!!.print.state)
        assertEquals(SyncState.LOCAL_ONLY, stored.sync.state)
        assertEquals(sale.ticketNumber.value, stored.sale.ticketNumber.value)
        assertEquals(listOf("ESP", "CRO"), stored.sale.lines.map { it.productId })
        assertTrue(fixture.printer.delivered.isEmpty(), "printing must not block the click")
        assertEquals(1, fixture.store.unsyncedCount(), "the sale is already waiting to be uploaded")
    }

    @Test
    fun `printing and syncing happen after the click, not during it`() = runTest {
        val fixture = PosFixture(scope = this)
        fixture.goOffline()

        val sale = fixture.sell("CAP" to 1)
        assertEquals(PrintState.PENDING, fixture.store.byId(sale.id)!!.print.state)
        assertTrue(fixture.printer.delivered.isEmpty())

        advanceUntilIdle()

        assertEquals(PrintState.PRINTED, fixture.store.byId(sale.id)!!.print.state)
        assertEquals(listOf(sale.ticketNumber.value), fixture.printer.delivered)
        fixture.close()
    }

    @Test
    fun `ticket numbers are dense with no gap across a hundred sales`() = runTest {
        val fixture = PosFixture(scope = this)
        fixture.goOffline()

        val tickets = (1..100).map { fixture.sell("ESP" to 1).ticketNumber.value }

        assertEquals(100, tickets.toSet().size)
        assertEquals((1L..100L).toList(), tickets.map { TicketNumber.sequenceOf(it) })
        assertEquals(1, tickets.map { it.substringBeforeLast('-') }.toSet().size)
        fixture.close()
    }

    @Test
    fun `a crashed process keeps the sale and its ticket number`(@TempDir directory: Path) {
        val database = directory.resolve("caisse.db").toString()
        val crashScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)

        val sale = PosFixture(scope = crashScope, databasePath = database).use { fixture ->
            fixture.goOffline()
            fixture.sell("QLR" to 1, "EAU" to 2)
        }

        val reopened = SqliteLocalStore.open(database)
        val recovered = reopened.byTicketNumber(sale.ticketNumber.value)
        assertNotNull(recovered, "the sale must survive an immediate crash after checkout")
        assertEquals(sale.id, recovered!!.sale.id)
        assertEquals(Money.ofMajor(9, 600), recovered.sale.total)
        assertEquals(PrintState.PENDING, recovered.print.state)
        assertEquals(1L, TicketNumber.sequenceOf(sale.ticketNumber.value))
        reopened.close()
    }

    @Test
    fun `a reopened terminal continues the same sequence`(@TempDir directory: Path) {
        val database = directory.resolve("caisse.db").toString()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val first = PosFixture(scope = scope, databasePath = database).use { it.sell("ESP" to 1).ticketNumber.value }
        val second = PosFixture(scope = scope, databasePath = database).use { it.sell("ESP" to 1).ticketNumber.value }

        assertEquals(1L, TicketNumber.sequenceOf(first))
        assertEquals(2L, TicketNumber.sequenceOf(second))
    }

    @Test
    fun `cart totals, change and stock-free arithmetic are exact`() {
        val cart = Cart()
        cart.add("ESP", 2)
        cart.add("PAC", 3)
        val snapshot = cart.snapshot()

        assertEquals(2 * 2200L + 3 * 1400L, snapshot.total.minor)
        assertEquals(Money.ofMajor(8, 600), snapshot.total)
        assertEquals(5, snapshot.itemCount)
    }

    @Test
    fun `cash payment computes change and refuses insufficient cash`() {
        val store = SqliteLocalStore.open(SqliteLocalStore.MEMORY)
        val terminal = store.terminal()
        val namespace = TicketNumber.issue(
            TerminalNamespace(terminal.id, terminal.namespaceLength),
            1,
        )
        val cart = Cart()
        cart.add("SJB", 1)
        val snapshot = cart.snapshot()

        val sale = Sale.fromCart(
            id = "id-1",
            ticketNumber = namespace,
            storeId = "s",
            terminalId = terminal.id,
            snapshot = snapshot,
            createdAtEpochMs = 1,
            paymentMethod = PaymentMethod.CASH,
            cashGiven = Money.ofMajor(10),
        )
        assertEquals(Money.ofMajor(3, 500), sale.changeDue)

        assertThrows<IllegalArgumentException> {
            Sale.fromCart(
                id = "id-2",
                ticketNumber = namespace,
                storeId = "s",
                terminalId = terminal.id,
                snapshot = snapshot,
                createdAtEpochMs = 1,
                paymentMethod = PaymentMethod.CASH,
                cashGiven = Money.ofMajor(1),
            )
        }
        store.close()
    }

    @Test
    fun `an empty cart cannot be checked out`() = runTest {
        val fixture = PosFixture(scope = this)
        assertThrows<IllegalArgumentException> {
            fixture.service.checkout(fixture.cartOf(), PaymentMethod.CARD, null)
        }
        assertTrue(fixture.store.history(10).isEmpty())
        assertEquals(0L, fixture.store.terminal().lastSequence, "no number may be burned by a refused checkout")
        fixture.close()
    }

    @Test
    fun `checkout uses a snapshot so the cart can be reused`() = runTest {
        val fixture = PosFixture(scope = this)
        val cart = Cart()
        cart.add("ESP", 1)
        val snapshot = cart.snapshot()

        val sale = fixture.service.checkout(snapshot, PaymentMethod.CARD, null)
        cart.clear()

        assertEquals(1, sale.lines.size)
        assertTrue(cart.isEmpty())
        assertFalse(snapshot.isEmpty)
        fixture.close()
    }

    @Test
    fun `the database refuses a duplicated ticket number`() = runTest {
        val fixture = PosFixture(scope = this)
        val sale = fixture.sell("ESP" to 1)
        val stored = fixture.store.byId(sale.id)!!

        val forged = stored.copy(sale = stored.sale.copy(id = "other-id"))

        assertThrows<java.sql.SQLException> {
            fixture.store.insert(forged)
        }
        assertNull(fixture.store.byId("other-id"))
        fixture.close()
    }

    @Test
    fun `the catalog holds exactly eight products`() {
        assertEquals(8, ProductCatalog.all.size)
        assertEquals(8, ProductCatalog.all.map { it.id }.toSet().size)
        assertNotNull(ProductCatalog.byId("ESP"))
        assertNull(ProductCatalog.byId("NOPE"))
    }
}
