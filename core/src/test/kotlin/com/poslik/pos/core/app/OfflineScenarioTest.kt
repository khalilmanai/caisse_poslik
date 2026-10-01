package com.poslik.pos.core.app

import com.poslik.pos.core.domain.PrintState
import com.poslik.pos.core.domain.PrintStatus
import com.poslik.pos.core.domain.SyncState
import com.poslik.pos.core.testing.PosFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineScenarioTest {

    @Test
    fun `the whole brief end to end - offline tillage then clean synchronisation`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)

        fixture.goOffline()
        val offlineTickets = (1..20).map { index ->
            fixture.sell(
                "ESP" to (index % 3 + 1),
                "CRO" to (index % 2 + 1),
                "EAU" to 1,
            )
        }
        fixture.printWorker.pumpUntilEmpty()

        assertEquals(20, fixture.service.history(50).size, "history is consultable with no network")
        assertTrue(fixture.service.history(50).all { it.printState == PrintState.PRINTED })
        assertTrue(fixture.service.history(50).all { it.syncState == SyncState.LOCAL_ONLY })
        assertEquals(0, fixture.cloud.saleCount(fixture.storeId))

        fixture.goOnline()
        val report = fixture.syncEngine.syncOnce()

        assertEquals(20, report.pushed)
        assertEquals(0, report.failed)
        assertEquals(0, report.conflicts)
        assertEquals(20, fixture.cloud.saleCount(fixture.storeId), "no duplicate in the cloud")
        offlineTickets.forEach { sale ->
            assertEquals(sale.id, fixture.cloud.ticketOwner(fixture.storeId, sale.ticketNumber.value))
        }
        assertEquals(0, fixture.store.unsyncedCount())
        assertTrue(fixture.service.history(50).all { it.syncState == SyncState.SYNCED })

        fixture.syncEngine.syncOnce()
        fixture.syncEngine.syncOnce()
        assertEquals(20, fixture.cloud.saleCount(fixture.storeId), "extra syncs stay idempotent")
        fixture.close()
    }

    @Test
    fun `a new sale taken while the backlog is uploading is never lost`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        repeat(5) { fixture.sell("CAP" to 1) }
        fixture.goOnline()

        fixture.cloud.transientFailures = 0
        val during = fixture.sell("SJB" to 1)
        val report = fixture.syncEngine.syncOnce()

        assertTrue(report.pushed >= 1)
        assertEquals(during.id, fixture.cloud.ticketOwner(fixture.storeId, during.ticketNumber.value))
        assertEquals(0, fixture.store.unsyncedCount())
        fixture.close()
    }

    @Test
    fun `a crash between the sale and the print is repaired on the next launch`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, online = false)
        val sale = fixture.sell("QLR" to 2)
        fixture.printWorker.pumpUntilEmpty()
        val state = fixture.store.byId(sale.id)!!.print.state
        assertEquals(PrintState.PRINTED, state, "printed in the first run")

        val second = fixture.sell("QLR" to 1)
        fixture.store.updatePrint(
            second.id,
            PrintStatus(
                state = PrintState.PRINTING,
                attempts = 0,
                nextAttemptAtEpochMs = Long.MAX_VALUE,
                updatedAtEpochMs = fixture.clock.now(),
            ),
        )

        val deliveredBefore = fixture.printer.delivered.size
        val report = fixture.service.onStartup()

        assertEquals(1, report.printQueueRecovered)
        assertEquals(1, report.print.printed)
        assertEquals(
            listOf(second.ticketNumber.value),
            fixture.printer.delivered.drop(deliveredBefore),
            "only the interrupted ticket is reprinted",
        )
        fixture.close()
    }
}
