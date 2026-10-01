package com.poslik.pos.core.domain

import java.util.Collections

data class CartLine(
    val product: Product,
    val quantity: Int,
) {
    val total: Money get() = product.unitPrice * quantity
}

data class CartSnapshot(
    val lines: List<CartLine>,
    val currency: String,
) {
    val total: Money = lines.fold(Money(0, currency)) { acc, line -> acc + line.total }
    val itemCount: Int = lines.sumOf { it.quantity }
    val isEmpty: Boolean get() = lines.isEmpty()
}

class Cart(private val currency: String = "TND") {

    private val quantities = LinkedHashMap<String, Int>()

    val currencyCode: String get() = currency

    @Synchronized
    fun add(productId: String, quantity: Int = 1): CartLine {
        require(quantity > 0) { "quantite invalide : $quantity" }
        val product = ProductCatalog.requireById(productId)
        val current = quantities[productId] ?: 0
        val updated = current + quantity
        quantities[productId] = updated
        return CartLine(product, updated)
    }

    @Synchronized
    fun setQuantity(productId: String, quantity: Int): CartLine? {
        val product = ProductCatalog.requireById(productId)
        if (quantity <= 0) {
            quantities.remove(productId)
            return null
        }
        quantities[productId] = quantity
        return CartLine(product, quantity)
    }

    @Synchronized
    fun remove(productId: String): Boolean = quantities.remove(productId) != null

    @Synchronized
    fun clear() = quantities.clear()

    @Synchronized
    fun isEmpty(): Boolean = quantities.isEmpty()

    @Synchronized
    fun snapshot(): CartSnapshot {
        val lines = quantities.map { (id, quantity) ->
            CartLine(ProductCatalog.requireById(id), quantity)
        }
        return CartSnapshot(Collections.unmodifiableList(lines), currency)
    }
}
