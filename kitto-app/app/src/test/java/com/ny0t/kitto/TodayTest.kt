package com.ny0t.kitto

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class TodayTest {

    @Test
    fun dateLineGoesBeforeTheQuestion() {
        assertEquals(
            "[Today is Tuesday, 2026-11-24.]\nspent rm5 yesterday",
            withToday("spent rm5 yesterday", "Today is Tuesday, 2026-11-24."),
        )
    }

    @Test
    fun malaysiaMorning() {
        // 2026-11-24T02:30Z = Tuesday 10:30 in Kuala Lumpur.
        val now = Date(1795487400000L)
        assertEquals(
            "Today is Tuesday, 2026-11-24, local time 10:30 (Asia/Kuala_Lumpur, UTC+08:00).",
            todayLine(now, TimeZone.getTimeZone("Asia/Kuala_Lumpur")),
        )
    }

    @Test
    fun localDayNotUtcDay() {
        // 2026-11-23T17:30Z is already Tuesday 01:30 in Kuala Lumpur, still Monday in UTC.
        val now = Date(1795455000000L)
        assertEquals(
            "Today is Tuesday, 2026-11-24, local time 01:30 (Asia/Kuala_Lumpur, UTC+08:00).",
            todayLine(now, TimeZone.getTimeZone("Asia/Kuala_Lumpur")),
        )
    }
}
