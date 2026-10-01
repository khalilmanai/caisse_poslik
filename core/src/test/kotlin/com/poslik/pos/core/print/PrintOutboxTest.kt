package com.poslik.pos.core.print

import com.poslik.pos.core.domain.PrintState
import com.poslik.pos.core.domain.PrintStatus
import com.poslik.pos.core.testing.PosFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrintOutboxTest {

    @Test
    fun `a pending ticket is printed exactly once`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, printerFailFirstAttempts = 0)
        val sale = fixture.sell("ESP" to 1)

        fixture.printWorker.pumpUntilEmpty()

        assertEquals(listOf(sale.ticketNumber.value), fixture.printer.delivered)
        assertEquals(PrintState.PRINTED, fixture.store.byId(sale.id)!!.print.state)
        fixture.close()
    }

    @Test
    fun `a failing printer leaves the ticket in echec and keeps it queued`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, printerFailFirstAttempts = 1)
        val sale = fixture.sell("ESP" to 1)

        val first = fixture.printWorker.pump()
        val afterFailure = fixture.store.byId(sale.id)!!.print
        assertEquals(PrintState.FAILED, afterFailure.state)
        assertEquals(1, afterFailure.attempts)
        assertNotNull(afterFailure.lastError)
        assertTrue(afterFailure.nextAttemptAtEpochMs > fixture.clock.now(), "a backoff must be scheduled")

        val immediateRetry = fixture.printWorker.pump()
        assertEquals(0, immediateRetry.attempted, "no retry before the backoff elapses")

        fixture.clock.advance(5_000)
        fixture.printWorker.pumpUntilEmpty()
        assertEquals(PrintState.PRINTED, fixture.store.byId(sale.id)!!.print.state)
        assertEquals(2, fixture.store.byId(sale.id)!!.print.attempts)
        fixture.close()
    }

    @Test
    fun `startup reprints pending and failed tickets but never printed ones`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, printerFailFirstAttempts = 1)

        val alreadyPrinted = fixture.sell("ESP" to 1)
        fixture.printWorker.pump()
        fixture.clock.advance(10_000)
        fixture.printWorker.pumpUntilEmpty()

        val failed = fixture.sell("CAP" to 1)
        fixture.printWorker.pump()

        assertEquals(PrintState.PRINTED, fixture.store.byId(alreadyPrinted.id)!!.print.state)
        assertEquals(PrintState.FAILED, fixture.store.byId(failed.id)!!.print.state)

        fixture.clock.advance(60_000)
        val deliveredBefore = fixture.printer.delivered.size
        val report = fixture.service.onStartup()

        assertEquals(1, report.printQueueRecovered, "only the failed ticket is requeued")
        assertEquals(1, report.print.printed)
        assertEquals(1, fixture.printer.delivered.size - deliveredBefore)
        assertEquals(PrintState.PRINTED, fixture.store.byId(failed.id)!!.print.state)
        assertFalse(
            fixture.printer.delivered.drop(deliveredBefore).contains(alreadyPrinted.ticketNumber.value),
            "an already printed ticket must never be reprinted at startup",
        )
        assertEquals(2, fixture.store.byId(alreadyPrinted.id)!!.print.attempts, "no new print attempt")
        fixture.close()
    }

    @Test
    fun `a ticket interrupted mid print is recovered at startup`() = runTest {
        val fixture = PosFixture(scope = backgroundScope)
        val sale = fixture.sell("ESP" to 1)

        fixture.store.updatePrint(
            sale.id,
            PrintStatus(PrintState.PRINTING, attempts = 0, nextAttemptAtEpochMs = Long.MAX_VALUE, updatedAtEpochMs = 1),
        )
        assertEquals(PrintState.PRINTING, fixture.store.byId(sale.id)!!.print.state)

        val report = fixture.service.onStartup()

        assertEquals(1, report.printQueueRecovered)
        assertEquals(PrintState.PRINTED, fixture.store.byId(sale.id)!!.print.state)
        assertEquals(listOf(sale.ticketNumber.value), fixture.printer.delivered)
        fixture.close()
    }

    @Test
    fun `printed is absorbing so reconciliation cannot downgrade it`() {
        val printed = PrintStatus(PrintState.PRINTED, attempts = 1, updatedAtEpochMs = 100, printedAtEpochMs = 100)
        val pending = PrintStatus(PrintState.PENDING, updatedAtEpochMs = 500)
        val failed = PrintStatus(PrintState.FAILED, attempts = 3, updatedAtEpochMs = 900)

        assertEquals(printed, PrintStatus.merge(printed, pending))
        assertEquals(printed, PrintStatus.merge(printed, failed))
        assertEquals(printed, PrintStatus.merge(pending, printed))
        assertEquals(failed, PrintStatus.merge(pending, failed))
        assertEquals(failed, PrintStatus.merge(failed, pending), "the most recent state wins")
        assertEquals(pending, PrintStatus.merge(pending, PrintStatus(PrintState.PRINTING, updatedAtEpochMs = 0)))
    }

    @Test
    fun `the print state machine forbids impossible transitions`() {
        assertTrue(PrintState.PENDING.canTransitionTo(PrintState.PRINTING))
        assertTrue(PrintState.PRINTING.canTransitionTo(PrintState.PRINTED))
        assertTrue(PrintState.PRINTING.canTransitionTo(PrintState.FAILED))
        assertTrue(PrintState.FAILED.canTransitionTo(PrintState.PRINTING))
        assertFalse(PrintState.PRINTED.canTransitionTo(PrintState.PRINTING))
        assertFalse(PrintState.PRINTED.canTransitionTo(PrintState.FAILED))
        assertFalse(PrintState.PRINTED.canTransitionTo(PrintState.PENDING))
    }

    @Test
    fun `the history exposes the print state required by the brief`() = runTest {
        val fixture = PosFixture(scope = backgroundScope, printerFailFirstAttempts = 99)
        val sale = fixture.sell("ESP" to 3)
        fixture.printWorker.pump()

        val entry = fixture.service.history(10).single { it.ticketNumber == sale.ticketNumber.value }
        assertEquals(PrintState.FAILED, entry.printState)
        assertEquals("\u00E9chec", entry.printStatusLabel)
        assertEquals(1, entry.attempts)
        assertNotNull(entry.lastError)
        assertEquals(3, entry.itemCount)
        assertEquals("6,600 DT", entry.total.toString())
        fixture.close()
    }
}
