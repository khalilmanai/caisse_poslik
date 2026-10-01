package com.poslik.pos.core.sync

import com.poslik.pos.core.data.SqliteLocalStore
import com.poslik.pos.core.domain.PrintState
import com.poslik.pos.core.domain.SyncState
import com.poslik.pos.core.domain.TerminalNamespace
import com.poslik.pos.core.domain.TicketNumber
import com.poslik.pos.core.testing.PosFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {

    private fun shortNamespace(terminalId: String, length: Int): String =
        TerminalNamespace(terminalId, length).short

    private fun namespaceOf(ticketNumber: String): String = ticketNumber.split("-")[1]

    @Test
    fun `sales made offline are uploaded on reconnect without loss or duplicate`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val sales = (1..12).map { fixture.sell("ESP" to it) }
        fixture.printWorker.pumpUntilEmpty()

        assertEquals(0, fixture.cloud.saleCount(fixture.storeId), "nothing may reach the cloud while offline")
        assertEquals(12, fixture.store.unsyncedCount())

        fixture.goOnline()
        val report = fixture.syncEngine.syncOnce()

        assertEquals(12, report.pushed)
        assertEquals(0, report.failed)
        assertEquals(0, fixture.store.unsyncedCount())
        assertEquals(12, fixture.cloud.saleCount(fixture.storeId), "exactly one node per sale")
        sales.forEach { sale ->
            assertEquals(sale.id, fixture.cloud.ticketOwner(fixture.storeId, sale.ticketNumber.value))
        }
        fixture.close()
    }

    @Test
    fun `replaying a sync never duplicates anything`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val sales = (1..5).map { fixture.sell("CAP" to 1) }

        fixture.goOnline()
        repeat(5) { fixture.syncEngine.syncOnce() }

        assertEquals(5, fixture.cloud.saleCount(fixture.storeId))
        assertEquals(5, fixture.store.history(50).size)
        sales.forEach {
            assertEquals(it.id, fixture.cloud.ticketOwner(fixture.storeId, it.ticketNumber.value))
        }
        fixture.close()
    }

    @Test
    fun `a partially failed upload retries only the missing sale`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val sales = (1..4).map { fixture.sell("ESP" to 1) }

        fixture.cloud.protect("stores/${fixture.storeId}/sales/${sales[1].id}")
        fixture.goOnline()
        val first = fixture.syncEngine.syncOnce()

        assertEquals(3, first.pushed)
        assertEquals(1, first.failed)
        assertEquals(SyncState.FAILED, fixture.store.byId(sales[1].id)!!.sync.state)
        assertEquals(3, fixture.cloud.saleCount(fixture.storeId))
        assertEquals(1, fixture.store.unsyncedCount())

        fixture.clock.advance(120_000)
        fixture.cloud.unprotect("stores/${fixture.storeId}/sales/${sales[1].id}")
        val second = fixture.syncEngine.syncOnce()

        assertEquals(1, second.pushed)
        assertEquals(4, fixture.cloud.saleCount(fixture.storeId))
        assertEquals(0, fixture.store.unsyncedCount())
        fixture.close()
    }

    @Test
    fun `sync is a no-op while the network is down`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        fixture.sell("ESP" to 1)

        val report = fixture.syncEngine.syncOnce()

        assertTrue(report.offline)
        assertEquals(0, report.pushed)
        assertEquals(0, fixture.cloud.patches, "no request may be attempted while offline")
        assertEquals(1, fixture.store.unsyncedCount())
        fixture.close()
    }

    @Test
    fun `a transient backend error does not lose the sale`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val sale = fixture.sell("ESP" to 1)
        fixture.goOnline()

        fixture.cloud.transientFailures = 1
        val report = fixture.syncEngine.syncOnce()

        assertTrue(report.failed > 0)
        assertEquals(0, fixture.cloud.saleCount(fixture.storeId))
        assertEquals(1, fixture.store.unsyncedCount())

        fixture.cloud.transientFailures = 0
        fixture.clock.advance(600_000)
        val retry = fixture.syncEngine.syncOnce()

        assertEquals(1, retry.pushed)
        assertEquals(1, fixture.cloud.saleCount(fixture.storeId))
        assertEquals(sale.id, fixture.cloud.ticketOwner(fixture.storeId, sale.ticketNumber.value))
        fixture.close()
    }

    @Test
    fun `a rejected index write leaves the sale queued and nothing is lost`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val sale = fixture.sell("ESP" to 1)
        fixture.goOnline()

        fixture.cloud.protect("stores/${fixture.storeId}/ticketIndex/${sale.ticketNumber.value}")
        val report = fixture.syncEngine.syncOnce()

        assertEquals(0, report.pushed, "the atomic update touches sale and index together")
        assertEquals(0, fixture.cloud.saleCount(fixture.storeId))
        assertEquals(1, fixture.store.unsyncedCount())
        assertNotNull(fixture.store.byId(sale.id), "the sale is still safe on the terminal")

        fixture.clock.advance(600_000)
        fixture.cloud.unprotect("stores/${fixture.storeId}/ticketIndex/${sale.ticketNumber.value}")
        val retry = fixture.syncEngine.syncOnce()
        assertEquals(1, retry.pushed)
        fixture.close()
    }

    @Test
    fun `a ticket index owned by another sale is reported as a conflict`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val sale = fixture.sell("ESP" to 1)
        fixture.goOnline()

        fixture.cloud.seed(
            fixture.storeId,
            JsonObject(
                mapOf(
                    "ticketIndex" to JsonObject(
                        mapOf(sale.ticketNumber.value to JsonPrimitive("sale-from-another-device")),
                    ),
                ),
            ),
        )
        fixture.cloud.dropTicketIndexWrites = true
        fixture.cloud.enforceTicketIndexRule = false

        val report = fixture.syncEngine.syncOnce()

        assertEquals(1, report.conflicts, "report=$report owner=${fixture.cloud.ticketOwner(fixture.storeId, sale.ticketNumber.value)}")
        val conflicted = fixture.store.byId(sale.id)!!.sync
        assertEquals(SyncState.CONFLICT, conflicted.state)
        assertNotNull(conflicted.conflictNote)
        assertTrue(conflicted.conflictNote!!.contains(sale.ticketNumber.value))
        assertNotNull(fixture.store.byId(sale.id), "a conflict is surfaced, never silently dropped")
        fixture.close()
    }

    @Test
    fun `a terminal namespace owned by another terminal is widened`() = runTest {
        val fixture = PosFixture(scope = this)
        val narrow = fixture.store.terminal().namespaceLength
        val contested = shortNamespace(fixture.terminalId, narrow)

        fixture.cloud.protect("stores/${fixture.storeId}/namespaces/$contested")
        val report = fixture.syncEngine.syncOnce()

        assertNotNull(report.namespaceLength)
        assertTrue(report.namespaceLength!! > narrow, "the namespace must be widened on collision")
        assertEquals(report.namespaceLength, fixture.store.terminal().namespaceLength)

        fixture.clock.advance()
        val sale = fixture.sell("ESP" to 1)
        assertEquals(
            shortNamespace(fixture.terminalId, report.namespaceLength!!),
            namespaceOf(sale.ticketNumber.value),
        )
        fixture.close()
    }

    @Test
    fun `the terminal publishes its sequence so a reinstall can resume it`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        repeat(7) { fixture.sell("ESP" to 1) }
        fixture.goOnline()
        fixture.syncEngine.syncOnce()

        val document = fixture.cloud.terminalDocument(fixture.storeId, fixture.terminalId)
        assertNotNull(document)
        assertEquals(7L, (document!!["lastTicketSequence"] as JsonPrimitive).content.toLong())
        fixture.close()
    }

    @Test
    fun `after a reinstall the same terminal resumes the sequence and reimports its history`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val before = (1..5).map { fixture.sell("ESP" to 1).ticketNumber.value }
        fixture.goOnline()
        fixture.syncEngine.syncOnce()
        val terminalId = fixture.terminalId
        val namespaceLength = fixture.store.terminal().namespaceLength
        val cloud = fixture.cloud
        val storeId = fixture.storeId
        val clock = fixture.clock
        fixture.close()

        val reinstalled = SqliteLocalStore.open(SqliteLocalStore.MEMORY, clock::now, restoredTerminalId = terminalId)
        val engine = SyncEngine(reinstalled, cloud, storeId, clock::now, isOnline = { true })
        runBlocking { engine.syncOnce() }

        assertEquals(terminalId, reinstalled.terminal().id)
        assertEquals(namespaceLength, reinstalled.terminal().namespaceLength)
        assertEquals(5L, reinstalled.terminal().lastSequence, "the counter resumes where it stopped")
        assertEquals(5, reinstalled.history(50).size, "the ticket history comes back from the cloud")

        val nextSequence = reinstalled.nextSequence()
        val nextTicket = TicketNumber.issue(TerminalNamespace(terminalId, namespaceLength), nextSequence)
        assertEquals(6L, nextTicket.sequence)
        assertTrue(before.none { it == nextTicket.value }, "a resumed number must not repeat an old one")
        reinstalled.close()
    }

    @Test
    fun `history stays readable offline and shows the local sync state`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        fixture.sell("ESP" to 1)
        fixture.sell("CRO" to 2)
        fixture.printWorker.pumpUntilEmpty()

        val history = fixture.service.history(10)

        assertEquals(2, history.size)
        assertTrue(history.all { it.syncState == SyncState.LOCAL_ONLY })
        assertTrue(history.all { it.printState == PrintState.PRINTED })
        fixture.close()
    }

    @Test
    fun `the pull cursor advances and known sales are not reimported`() = runTest {
        val fixture = PosFixture(scope = this)
        fixture.sell("ESP" to 1)
        fixture.syncEngine.syncOnce()
        val cursorAfterFirst = fixture.store.syncCursor()
        assertTrue(cursorAfterFirst > 0)

        fixture.sell("ESP" to 1)
        val second = fixture.syncEngine.syncOnce()

        assertEquals(1, second.pushed)
        assertTrue(fixture.store.syncCursor() >= cursorAfterFirst)
        assertEquals(2, fixture.store.history(10).size)
        assertEquals(2, fixture.cloud.saleCount(fixture.storeId))
        fixture.close()
    }

    @Test
    fun `a sale from another terminal is pulled without moving our counter`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        fixture.sell("ESP" to 1)
        val sequenceBefore = fixture.store.terminal().lastSequence
        fixture.goOnline()
        fixture.syncEngine.syncOnce()

        val otherTerminal = "11111111-2222-4333-8444-555555555555"
        fixture.cloud.seed(
            fixture.storeId,
            JsonObject(
                mapOf(
                    "sales" to JsonObject(
                        mapOf(
                            "remote-1" to JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("remote-1"),
                                    "ticketNumber" to JsonPrimitive("T-DEADBE-000009"),
                                    "terminalId" to JsonPrimitive(otherTerminal),
                                    "storeId" to JsonPrimitive(fixture.storeId),
                                    "currency" to JsonPrimitive("TND"),
                                    "totalMinor" to JsonPrimitive(3800),
                                    "createdAt" to JsonPrimitive(fixture.clock.now() + 5),
                                    "paymentMethod" to JsonPrimitive("CASH"),
                                    "changeDueMinor" to JsonPrimitive(0),
                                    "printState" to JsonPrimitive("PRINTED"),
                                    "printUpdatedAt" to JsonPrimitive(fixture.clock.now() + 5),
                                    "updatedAt" to JsonPrimitive(fixture.clock.now() + 5),
                                    "lines" to JsonArray(emptyList()),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val pulled = fixture.syncEngine.pullChanges()

        assertEquals(1, pulled)
        val imported = fixture.store.byId("remote-1")
        assertNotNull(imported)
        assertEquals("T-DEADBE-000009", imported!!.sale.ticketNumber.value)
        assertEquals(PrintState.PRINTED, imported.print.state)
        assertNull(imported.sync.conflictNote)
        assertEquals(sequenceBefore, fixture.store.terminal().lastSequence)
        fixture.close()
    }

    @Test
    fun `a pull degrades to a full fetch when the deployed rules have no index`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        fixture.sell("ESP" to 1)
        fixture.goOnline()
        fixture.syncEngine.syncOnce()

        val otherTerminal = "11111111-2222-4333-8444-555555555555"
        fixture.cloud.seed(
            fixture.storeId,
            JsonObject(
                mapOf(
                    "sales" to JsonObject(
                        mapOf(
                            "remote-noindex" to JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive("remote-noindex"),
                                    "ticketNumber" to JsonPrimitive("T-DEADBE-000042"),
                                    "terminalId" to JsonPrimitive(otherTerminal),
                                    "storeId" to JsonPrimitive(fixture.storeId),
                                    "currency" to JsonPrimitive("TND"),
                                    "totalMinor" to JsonPrimitive(3800),
                                    "createdAt" to JsonPrimitive(fixture.clock.now() + 5),
                                    "paymentMethod" to JsonPrimitive("CASH"),
                                    "changeDueMinor" to JsonPrimitive(0),
                                    "printState" to JsonPrimitive("PRINTED"),
                                    "printUpdatedAt" to JsonPrimitive(fixture.clock.now() + 5),
                                    "updatedAt" to JsonPrimitive(fixture.clock.now() + 5),
                                    "lines" to JsonArray(emptyList()),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        var orderByCalls = 0
        val noIndexInstance = object : RealtimeDbGateway {
            override val description: String = "instance-without-index"
            override suspend fun patch(path: String, updates: Map<String, kotlinx.serialization.json.JsonElement>, printSilent: Boolean) =
                fixture.cloud.patch(path, updates, printSilent)
            override suspend fun get(path: String, query: RealtimeQuery): kotlinx.serialization.json.JsonElement? {
                if (query.orderBy != null) {
                    orderByCalls++
                    throw RealtimeDbException(
                        400,
                        """{"error":"Index not defined, add \".indexOn\": \"updatedAt\""}""",
                        path,
                    )
                }
                return fixture.cloud.get(path, query)
            }
        }
        val engine = SyncEngine(
            store = fixture.store,
            gateway = noIndexInstance,
            storeId = fixture.storeId,
            clock = fixture.clock::now,
            isOnline = { true },
        )

        val report = engine.syncOnce()

        assertEquals(0, report.failed, "the fallback must absorb the 400, report=$report")
        assertEquals(1, report.pulled, "the sale must still arrive via the plain fetch")
        val imported = fixture.store.byId("remote-noindex")
        assertNotNull(imported)
        assertEquals("T-DEADBE-000042", imported!!.sale.ticketNumber.value)
        assertEquals(1, orderByCalls, "the refused query must be tried exactly once")

        val secondReport = engine.syncOnce()

        assertEquals(0, secondReport.failed, "report=$secondReport")
        assertEquals(1, orderByCalls, "the refused query must not be retried after the first 400")
        fixture.close()
    }
}
