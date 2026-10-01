package com.poslik.pos.core.domain

import kotlin.math.absoluteValue

/**
 * Montant monetaire inchangeable, stocke en unite mineure.
 *
 * L'unite mineure depend de la devise : **millimes pour TND** (1 DT = 1000 millimes),
 * centimes pour les autres devises (1 unite = 100 centimes).
 */
data class Money(val minor: Long, val currency: String = "TND") : Comparable<Money> {

    init {
        require(minor >= 0) { "negative amount: $minor" }
    }

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(minor + other.minor, currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(minor - other.minor, currency)
    }

    operator fun times(quantity: Int): Money = Money(minor * quantity, currency)

    fun isZero(): Boolean = minor == 0L

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minor.compareTo(other.minor)
    }

    override fun toString(): String = format()

    fun format(): String = formatMinor(minor, currency)

    fun formatPlain(): String = formatMinor(minor, currency, useSymbol = false)

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) { "currency mismatch: $currency vs ${other.currency}" }
    }

    companion object {

        /** Nombre de decimales de la devise : 3 pour le dinar tunisien (millimes), sinon 2. */
        fun fractionDigits(currency: String): Int = if (currency == "TND") 3 else 2

        private fun factor(currency: String): Long = when (fractionDigits(currency)) {
            3 -> 1_000L
            else -> 100L
        }

        /** [fraction] s'ecrit avec autant de chiffres que la devise en a (200 = 200 millimes). */
        fun ofMajor(major: Long, fraction: Long = 0, currency: String = "TND"): Money =
            Money(major * factor(currency) + fraction, currency)

        fun formatMinor(amount: Long, currency: String = "TND", useSymbol: Boolean = true): String {
            val sign = if (amount < 0) "-" else ""
            val absolute = amount.absoluteValue
            val base = factor(currency)
            val units = absolute / base
            val fraction = absolute % base
            val grouped = groupDigits(units)
            val suffix = if (!useSymbol) currency else (SYMBOLS[currency] ?: "$currency ")
            return "$sign$grouped,${fraction.toString().padStart(fractionDigits(currency), '0')} $suffix"
        }

        private fun groupDigits(value: Long): String {
            val digits = value.toString()
            if (digits.length <= 3) return digits
            val builder = StringBuilder()
            var count = 0
            for (index in digits.indices.reversed()) {
                builder.append(digits[index])
                count++
                if (count % 3 == 0 && index != 0) builder.append('\u202F')
            }
            return builder.reverse().toString()
        }

        private val SYMBOLS = mapOf("EUR" to "\u20AC", "USD" to "$", "MAD" to "DH", "TND" to "DT")
    }
}
