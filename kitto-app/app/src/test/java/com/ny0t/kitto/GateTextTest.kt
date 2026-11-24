package com.ny0t.kitto

import org.junit.Assert.assertEquals
import org.junit.Test

class GateTextTest {

    @Test
    fun logTransactionArgsBecomeReadableLines() {
        val args = linkedMapOf(
            "type" to "expense",
            "amount" to 15,
            "description" to "Lunch",
            "budgetName" to "Food",
        )
        assertEquals(
            "Type: expense\nAmount: 15\nDescription: Lunch\nBudget name: Food",
            formatArgs(args),
        )
    }

    @Test
    fun wholeDoublesDropTheDecimalOthersKeepIt() {
        assertEquals("Amount: 15", formatArgs(mapOf("amount" to 15.0)))
        assertEquals("Amount: 12.5", formatArgs(mapOf("amount" to 12.5)))
    }

    @Test
    fun labelsSplitCamelAndSnakeCase() {
        assertEquals("Budget name", label("budgetName"))
        assertEquals("Budget name", label("budget_name"))
        assertEquals("Date", label("date"))
    }

    @Test
    fun nullNestedAndListValues() {
        assertEquals("Note: none", formatArgs(mapOf("note" to null)))
        assertEquals("Tags: a, b", formatArgs(mapOf("tags" to listOf("a", "b"))))
        assertEquals("Where: {city: KL}", formatArgs(mapOf("where" to mapOf("city" to "KL"))))
    }

    @Test
    fun noArgsIsEmpty() {
        assertEquals("", formatArgs(emptyMap<String, Any>()))
    }
}
