package com.ny0t.kitto

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The date line put at the start of each new user message, so Claude can turn "yesterday" or
 * "last Friday" into a YYYY-MM-DD date. Claude has no clock of its own.
 * Not in the system prompt: that must never change mid-conversation (see SdkConversation.system).
 * java.util (not java.time) because minSdk is 24.
 */
fun withToday(question: String, line: String = todayLine()): String = "[$line]\n$question"

fun todayLine(now: Date = Date(), zone: TimeZone = TimeZone.getDefault()): String {
    fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.ENGLISH).apply { timeZone = zone }.format(now)
    return "Today is ${fmt("EEEE")}, ${fmt("yyyy-MM-dd")}, local time ${fmt("HH:mm")} " +
        "(${zone.id}, UTC${fmt("XXX")})."
}
