package com.poslik.pos.core.domain

import java.security.SecureRandom
import kotlin.random.Random

data class TerminalNamespace(
    val terminalId: String,
    val length: Int,
) {
    init {
        require(length in MIN_LENGTH..MAX_LENGTH) { "namespace length out of range: $length" }
    }

    val short: String get() = TicketNumber.compactHex(terminalId).take(length)

    companion object {
        const val MIN_LENGTH = 6
        const val MAX_LENGTH = 32
        val DEFAULT = TerminalNamespace(terminalId = "", length = MIN_LENGTH)
    }
}

data class TicketNumber(
    val value: String,
    val sequence: Long,
    val terminalId: String,
    val namespace: String,
) {
    companion object {
        const val SEQUENCE_WIDTH = 6

        fun issue(namespace: TerminalNamespace, sequence: Long): TicketNumber {
            require(sequence >= 0) { "sequence must not be negative, was $sequence" }
            val padded = sequence.toString().padStart(SEQUENCE_WIDTH, '0')
            val value = "T-${namespace.short}-$padded"
            return TicketNumber(value, sequence, namespace.terminalId, namespace.short)
        }

        fun parse(value: String, terminalId: String): TicketNumber {
            val segments = value.split('-')
            val sequence = segments.lastOrNull()?.toLongOrNull() ?: 0L
            val namespace = if (segments.size >= 3) segments[segments.size - 2] else ""
            return TicketNumber(value, sequence, terminalId, namespace)
        }

        fun sequenceOf(value: String): Long = parse(value, "").sequence

        fun compactHex(terminalId: String): String {
            val compact = terminalId.replace("-", "").uppercase()
            require(compact.isNotEmpty() && compact.all { it.isDigit() || it in 'A'..'F' }) {
                "terminalId must be a hex UUID, was '$terminalId'"
            }
            return compact
        }

        fun randomTerminalId(random: Random): String {
            val hex = ByteArray(16).also { random.nextBytes(it) }.toHex()
            return buildString {
                append(hex, 0, 8).append('-')
                append(hex, 8, 12).append('-')
                append(hex, 12, 16).append('-')
                append(hex, 16, 20).append('-')
                append(hex, 20, 32)
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") {
            val value = it.toInt() and 0xFF
            HEX[value ushr 4].toString() + HEX[value and 0x0F]
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

object TerminalIdGenerator {

    private val secureRandom = SecureRandom()

    fun newTerminalId(): String = TicketNumber.randomTerminalId(Random(secureRandom.nextLong()))
}
