package com.poslik.pos.core.domain

data class SaleLine(
    val productId: String,
    val name: String,
    val unitPrice: Money,
    val quantity: Int,
) {
    val total: Money get() = unitPrice * quantity

    companion object {
        fun from(cartLine: CartLine): SaleLine =
            SaleLine(cartLine.product.id, cartLine.product.name, cartLine.product.unitPrice, cartLine.quantity)
    }
}

enum class PaymentMethod { CASH, CARD }

data class Sale(
    val id: String,
    val ticketNumber: TicketNumber,
    val storeId: String,
    val terminalId: String,
    val currency: String,
    val lines: List<SaleLine>,
    val total: Money,
    val createdAtEpochMs: Long,
    val paymentMethod: PaymentMethod,
    val cashGiven: Money?,
    val changeDue: Money,
) {
    val itemCount: Int get() = lines.sumOf { it.quantity }

    companion object {
        fun fromCart(
            id: String,
            ticketNumber: TicketNumber,
            storeId: String,
            terminalId: String,
            snapshot: CartSnapshot,
            createdAtEpochMs: Long,
            paymentMethod: PaymentMethod,
            cashGiven: Money?,
        ): Sale {
            require(!snapshot.isEmpty) { "impossible d'encaisser un panier vide" }
            val change = when (paymentMethod) {
                PaymentMethod.CASH -> {
                    val given = requireNotNull(cashGiven) { "un paiement en especes exige le montant recu" }
                    require(given >= snapshot.total) { "montant insuffisant : $given < ${snapshot.total}" }
                    given - snapshot.total
                }

                PaymentMethod.CARD -> Money(0, snapshot.currency)
            }
            return Sale(
                id = id,
                ticketNumber = ticketNumber,
                storeId = storeId,
                terminalId = terminalId,
                currency = snapshot.currency,
                lines = snapshot.lines.map { SaleLine.from(it) },
                total = snapshot.total,
                createdAtEpochMs = createdAtEpochMs,
                paymentMethod = paymentMethod,
                cashGiven = cashGiven,
                changeDue = change,
            )
        }
    }
}
