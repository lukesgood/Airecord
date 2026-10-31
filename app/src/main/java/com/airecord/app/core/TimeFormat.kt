package com.airecord.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFormat {
    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
