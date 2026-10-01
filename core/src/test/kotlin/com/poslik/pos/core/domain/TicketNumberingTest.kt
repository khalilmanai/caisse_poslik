package com.poslik.pos.core.domain

import com.poslik.pos.core.data.SqliteLocalStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class TicketNumberingTest {

    private fun namespaceOf(ticketNumber: String): String = ticketNumber.split("-")[1]

    private fun sequenceOf(ticketNumber: String): Long = ticketNumber.split("-").last().toLong()

    @Test
    fun `numbers are dense and strictly increasing while offline`() {
        val store = SqliteLocalStore.open(SqliteLocalStore.MEMORY)
        val terminal = store.terminal()
        val namespace = TerminalNamespace(terminal.id, terminal.namespaceLength)

        val issued = (1L..25L).map { TicketNumber.issue(namespace, it) }

        assertEquals(issued.map { it.sequence }, (1L..25L).toList())
        assertEquals(issued.map { it.value }.toSet().size, 25)
        assertEquals(issued.map { it.value }.sorted(), issued.map { it.value })
        store.close()
    }

    @Test
    fun `two terminals selling offline at the same time never collide`() {
        val namespaceA = TerminalNamespace(TicketNumber.randomTerminalId(Random(1)), 6)
        val namespaceB = TerminalNamespace(TicketNumber.randomTerminalId(Random(2)), 6)
        val namespaceC = TerminalNamespace(TicketNumber.randomTerminalId(Random(3)), 6)

        val all = buildList {
            repeat(500) { add(TicketNumber.issue(namespaceA, it + 1L)) }
            repeat(500) { add(TicketNumber.issue(namespaceB, it + 1L)) }
            repeat(500) { add(TicketNumber.issue(namespaceC, it + 1L)) }
        }

        assertEquals(1500, all.map { it.value }.toSet().size, "every ticket number must be globally unique")
        assertEquals(3, all.map { namespaceOf(it.value) }.toSet().size, "namespaces must be disjoint")
    }

    @Test
    fun `collision probability of random namespaces is negligible`() {
        val namespaces = (1..20_000).map { TicketNumber.randomTerminalId(Random(it)).let { id -> TerminalNamespace(id, 6).short } }
        val distinct = namespaces.toSet().size
        assertTrue(
            distinct >= 19_990,
            "expected almost no collision in 20000 six-hex namespaces, got ${20_000 - distinct} collisions",
        )
    }

    @Test
    fun `format is stable and round-trips`() {
        val terminalId = "3F2A9C11-7B4D-4E2A-9C10-5D6E7F801234"
        val namespace = TerminalNamespace(terminalId, 6)
        val ticket = TicketNumber.issue(namespace, 42)

        assertEquals("T-3F2A9C-000042", ticket.value)
        val parsed = TicketNumber.parse(ticket.value, terminalId)
        assertEquals(42L, parsed.sequence)
        assertEquals(ticket.value, parsed.value)
        assertEquals(terminalId, parsed.terminalId)
    }

    @Test
    fun `namespace can be widened to resolve a collision`() {
        val terminalId = "3F2A9C11-7B4D-4E2A-9C10-5D6E7F801234"
        val narrow = TerminalNamespace(terminalId, 6)
        val wide = TerminalNamespace(terminalId, 12)

        assertEquals("3F2A9C", narrow.short)
        assertEquals("3F2A9C117B4D", wide.short)
        assertNotEquals(TicketNumber.issue(narrow, 7).value, TicketNumber.issue(wide, 7).value)
    }

    @Test
    fun `sequence cannot be negative`() {
        val namespace = TerminalNamespace(TicketNumber.randomTerminalId(Random(9)), 6)
        assertThrows<IllegalArgumentException> { TicketNumber.issue(namespace, -1) }
    }

    @Test
    fun `terminal ids are uuid v4 shaped and unique`() {
        val ids = (1..2_000).map { TerminalIdGenerator.newTerminalId() }
        assertEquals(2_000, ids.toSet().size)
        ids.forEach { id ->
            assertEquals(36, id.length)
            assertEquals(4, id.count { it == '-' })
            assertNotEquals("00000000-0000-0000-0000-000000000000", id)
        }
    }

    @Test
    fun `sequence is recovered from a printed ticket number`() {
        assertEquals(7L, TicketNumber.sequenceOf("T-ABCDEF-000007"))
        assertEquals(123456L, TicketNumber.sequenceOf("T-ABCDEF-123456"))
    }
}
