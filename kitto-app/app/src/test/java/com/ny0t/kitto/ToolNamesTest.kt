package com.ny0t.kitto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolNamesTest {

    @Test
    fun prefixComesFromLastPackageSegment() {
        assertEquals("buckt", prefixFor("com.navitech.buckt"))
    }

    @Test
    fun prefixStripsCharactersThatBreakClaudeNames() {
        assertEquals("my2app", prefixFor("com.example.my-2_app"))
    }

    @Test
    fun claudeNameRoundTrips() {
        val name = toClaudeName("buckt", "get_budget_balances")
        assertEquals("buckt__get_budget_balances", name)
        assertEquals("buckt" to "get_budget_balances", fromClaudeName(name))
    }

    @Test
    fun nameWithoutSeparatorIsNotSplit() {
        assertNull(fromClaudeName("nounderscore"))
    }

    @Test
    fun nameOver64CharactersIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            toClaudeName("buckt", "x".repeat(60))
        }
    }

    @Test
    fun toolNameWithDotIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            toClaudeName("buckt", "get.balances")
        }
    }
}
