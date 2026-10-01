package com.poslik.pos.core.domain

import java.security.SecureRandom

object PushId {

    private const val TIMESTAMP_CHARS = 8
    private const val RANDOM_CHARS = 12
    private const val CHARS = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"

    private val secureRandom = SecureRandom()
    private var lastTimestamp = -1L
    private var lastRandom = ByteArray(RANDOM_CHARS).also { secureRandom.nextBytes(it) }

    @Synchronized
    fun generate(nowEpochMs: Long = System.currentTimeMillis()): String {
        val sameMillisecond = nowEpochMs == lastTimestamp
        lastTimestamp = nowEpochMs
        if (sameMillisecond) incrementRandom()
        return encodeTimestamp(nowEpochMs) + encodeRandom(lastRandom)
    }

    private fun encodeTimestamp(timestamp: Long): String {
        var remaining = timestamp
        val builder = StringBuilder(TIMESTAMP_CHARS)
        for (index in 0 until TIMESTAMP_CHARS) {
            builder.append(CHARS[(remaining and 0xF).toInt()])
            remaining = remaining shr 4
        }
        return builder.reverse().toString()
    }

    private fun encodeRandom(input: ByteArray): String = buildString(input.size) {
        for (byte in input) append(CHARS[(byte.toInt() and 0xFF) % CHARS.length])
    }

    private fun incrementRandom() {
        for (index in lastRandom.indices.reversed()) {
            val current = lastRandom[index].toInt() and 0xFF
            val next = (current + 1) % CHARS.length
            lastRandom[index] = next.toByte()
            if (next != 0) return
        }
    }
}
