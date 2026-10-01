package com.poslik.pos.core.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFormat {

    private val ticketFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    private val historyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val fileFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    fun ticket(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        ticketFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun history(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        historyFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun fileStamp(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        fileFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
