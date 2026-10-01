package com.poslik.pos.core.print

import com.poslik.pos.core.domain.PaymentMethod
import com.poslik.pos.core.domain.Sale
import com.poslik.pos.core.util.TimeFormat
import java.time.ZoneId

class TicketRenderer(
    private val storeLabel: String,
    private val width: Int = 44,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val rule = "-".repeat(width)

    fun render(sale: Sale): String = buildString {
        appendLine(centered("TICKET ${sale.ticketNumber.value}"))
        appendLine(centered(storeLabel))
        appendLine(rule)
        appendLine(row("Date", TimeFormat.ticket(sale.createdAtEpochMs, zone)))
        appendLine(row("Terminal", TicketNamespaces.short(sale.terminalId)))
        appendLine(row("Ticket", sale.ticketNumber.value))
        appendLine(rule)
        sale.lines.forEach { line ->
            appendLine(row("${line.quantity} x ${line.name}", line.total.format()))
            if (line.quantity > 1) {
                appendLine("    prix unitaire " + line.unitPrice.format())
            }
        }
        appendLine(rule)
        appendLine(row("TOTAL", sale.total.format()))
        appendLine(row(paymentLabel(sale.paymentMethod), (sale.cashGiven ?: sale.total).format()))
        if (sale.paymentMethod == PaymentMethod.CASH && !sale.changeDue.isZero()) {
            appendLine(row("RENDU", sale.changeDue.format()))
        }
        appendLine(rule)
        appendLine(centered("Merci de votre visite"))
    }

    fun centered(content: String): String {
        val text = content.take(width)
        val padding = ((width - text.length) / 2).coerceAtLeast(0)
        return " ".repeat(padding) + text
    }

    fun row(leftLabel: String, rightValue: String): String {
        val right = rightValue.take(16)
        val left = leftLabel.take((width - right.length - 1).coerceAtLeast(1))
        return left.padEnd(width - right.length) + right
    }

    private fun paymentLabel(method: PaymentMethod): String = when (method) {
        PaymentMethod.CASH -> "PAIEMENT ESP\u00C8CES RE\u00C7U"
        PaymentMethod.CARD -> "PAIEMENT CB"
    }
}

object TicketNamespaces {

    fun short(terminalId: String): String = terminalId.replace("-", "").uppercase().take(6)
}
