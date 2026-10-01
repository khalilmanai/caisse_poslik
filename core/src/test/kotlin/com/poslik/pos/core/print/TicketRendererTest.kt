package com.poslik.pos.core.print

import com.poslik.pos.core.domain.Money
import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.domain.SaleLine
import com.poslik.pos.core.domain.TicketNumber
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneOffset

class TicketRendererTest {

    private val ticket = TicketNumber.parse("T-3F2A9C-000042", "3F2A9C11-7B4D-4E2A-9C10-5D6E7F801234")

    private fun sale(
        lines: List<SaleLine>,
        method: PaymentMethod = PaymentMethod.CASH,
        cashGiven: Money? = Money.ofMajor(20),
        total: Money = Money(lines.sumOf { it.unitPrice.minor * it.quantity }),
    ): Sale {
        val change = if (method == PaymentMethod.CASH && cashGiven != null) cashGiven - total else Money(0)
        return Sale(
            id = "sale-1",
            ticketNumber = ticket,
            storeId = "boutique",
            terminalId = "3F2A9C11-7B4D-4E2A-9C10-5D6E7F801234",
            currency = "TND",
            lines = lines,
            total = total,
            createdAtEpochMs = 1_760_000_000_000,
            paymentMethod = method,
            cashGiven = cashGiven,
            changeDue = change,
        )
    }

    private fun espresso(quantity: Int) = SaleLine("ESP", "Espresso", Money.ofMajor(2, 200), quantity)

    private fun render(subject: Sale, width: Int = 44): List<String> =
        TicketRenderer("Boutique Centre-ville", width, ZoneOffset.UTC)
            .render(subject)
            .lines()
            .filter { line: String -> line.isNotBlank() }

    @Test
    fun `the ticket carries the number, the lines, the total and the change`() {
        val text = render(sale(listOf(espresso(2), SaleLine("CRO", "Croissant", Money.ofMajor(1, 200), 1))))

        assertTrue(text.any { it.contains("TICKET T-3F2A9C-000042") }, text.toString())
        assertTrue(text.any { it.contains("Boutique Centre-ville") })
        assertTrue(text.any { it.contains("2 x Espresso") })
        assertTrue(text.any { it.contains("4,400 DT") }, "the line total must be printed")
        assertTrue(text.any { it.contains("prix unitaire") }, "a unit price is shown for multi-quantities")
        assertTrue(
            text.any { it.trim() == "TOTAL" + " ".repeat(31) + "5,600 DT" },
            text.toString(),
        )
        assertTrue(text.any { it.contains("PAIEMENT ESP\u00C8CES RE\u00C7U") && it.contains("20,000 DT") })
        assertTrue(text.any { it.trim().startsWith("RENDU") && it.contains("14,400 DT") })
        assertTrue(text.any { it.contains("3F2A9C") }, "the terminal namespace is printed")
    }

    @Test
    fun `a card payment does not pretend to know the cash given`() {
        val text = render(sale(listOf(espresso(1)), method = PaymentMethod.CARD, cashGiven = null))
        assertTrue(text.any { it.contains("PAIEMENT CB") }, text.toString())
        assertTrue(text.none { it.contains("RENDU") }, "no change line for a card payment")
    }

    @Test
    fun `the receipt has a stable width and aligned amounts`() {
        val width = 44
        val lines = render(sale(listOf(espresso(1))), width)
        assertTrue(lines.all { it.length <= width }, "no line may overflow the paper width: $lines")
        assertTrue(lines.count { it.all { character -> character == '-' } } >= 3, "rules separate the sections")
    }

    @Test
    fun `an over long product name is truncated instead of wrapping`() {
        val longLine = SaleLine("XXX", "Un produit au nom interminable de tres longue longueur", Money.ofMajor(9, 99), 1)
        val lines = render(sale(listOf(longLine)), width = 40)
        assertTrue(lines.all { it.length <= 40 }, lines.toString())
    }

    @Test
    fun `the spool gateway writes one utf8 file per ticket`(@TempDir directory: Path) {
        val spool = directory.resolve("tickets")
        val subject = sale(listOf(espresso(1)))

        runBlocking { PrintSpoolGateway(spool).print(subject, attempt = 1) }

        val files: List<Path> = Files.list(spool).use { stream -> stream.toList() }
        assertEquals(1, files.size)
        val content = String(Files.readAllBytes(files[0]), Charsets.UTF_8)
        assertTrue(content.contains("TICKET T-3F2A9C-000042"), content)
        assertTrue(content.contains("DT"), "the currency marker must be stored as utf8")
        assertTrue(files[0].fileName.toString().endsWith("T-3F2A9C-000042.txt"), files[0].toString())
        val leftovers: List<String> = Files.list(spool).use { stream ->
            stream.map { entry: Path -> entry.fileName.toString() }.toList()
        }
        assertTrue(leftovers.none { name: String -> name.endsWith(".tmp") }, "no temporary file may remain: $leftovers")
    }

    @Test
    fun `an unwritable spool raises a print failure instead of losing the ticket`(@TempDir directory: Path) {
        val blocker = directory.resolve("not-a-directory")
        Files.writeString(blocker, "occupied")

        val failure = assertThrows<PrintFailure> {
            runBlocking { PrintSpoolGateway(directory.resolve("not-a-directory").resolve("tickets")).print(sale(listOf(espresso(1))), 1) }
        }
        assertTrue(failure.message!!.contains("impossible d'\u00E9crire le ticket"), failure.message!!)
    }
}