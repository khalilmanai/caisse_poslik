package com.poslik.pos.core.domain

data class Product(
    val id: String,
    val name: String,
    val unitPrice: Money,
) {
    val priceMinor: Long get() = unitPrice.minor
}

object ProductCatalog {

    val all: List<Product> = listOf(
        Product("ESP", "Espresso", Money.ofMajor(2, 200)),
        Product("ALL", "Allonge", Money.ofMajor(2, 500)),
        Product("CAP", "Cappuccino", Money.ofMajor(3, 800)),
        Product("CRO", "Croissant", Money.ofMajor(1, 200)),
        Product("PAC", "Pain au chocolat", Money.ofMajor(1, 400)),
        Product("SJB", "Sandwich jambon-beurre", Money.ofMajor(6, 500)),
        Product("QLR", "Quiche lorraine", Money.ofMajor(5, 800)),
        Product("EAU", "Eau minerale 50cl", Money.ofMajor(1, 900)),
    ).also { products ->
        require(products.size == 8) { "the brief requires exactly 8 hardcoded products" }
        require(products.map { it.id }.toSet().size == products.size) { "duplicate product id" }
    }

    private val byId: Map<String, Product> = all.associateBy { it.id }

    fun byId(id: String): Product? = byId[id]

    fun requireById(id: String): Product =
        byId[id] ?: throw NoSuchElementException("unknown product id: $id")
}
