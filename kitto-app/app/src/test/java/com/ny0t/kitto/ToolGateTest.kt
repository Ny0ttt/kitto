package com.ny0t.kitto

import com.ny0t.kitto.protocol.ToolSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGateTest {

    private val readSpec = ToolSpec("get_budget_balances", "Balances", readOnly = true, parameters = "{}")
    // Fake mutating tool: no real one exists in M2.
    private val writeSpec = ToolSpec("log_transaction", "Log a spend", readOnly = false, parameters = "{}")

    private val tools = mapOf(
        "buckt__get_budget_balances" to RoutedTool("buckt-handle", "Buckt", readSpec),
        "buckt__log_transaction" to RoutedTool("buckt-handle", "Buckt", writeSpec),
    )

    private fun router(userSays: Boolean, asked: MutableList<GateRequest>, called: MutableList<String>) =
        ToolRouter(
            tools,
            ToolGate { request -> asked += request; userSays },
        ) { provider, toolName, _ -> called += "$provider:$toolName"; """{"ok":true}""" }

    @Test
    fun readOnlyToolSkipsTheGate() = runBlocking {
        val asked = mutableListOf<GateRequest>(); val called = mutableListOf<String>()
        val out = router(false, asked, called).run(ToolCall("t1", "buckt__get_budget_balances", "{}"))
        assertFalse(out.isError)
        assertTrue(asked.isEmpty())
        assertEquals(listOf("buckt-handle:get_budget_balances"), called)
    }

    @Test
    fun deniedMutatingToolReturnsErrorAndNeverCallsTheApp() = runBlocking {
        val asked = mutableListOf<GateRequest>(); val called = mutableListOf<String>()
        val out = router(false, asked, called).run(ToolCall("t2", "buckt__log_transaction", """{"amount":5}"""))
        assertTrue(out.isError)
        assertEquals(DECLINED_BY_USER, out.content)
        assertEquals(GateRequest("Buckt", "log_transaction", """{"amount":5}"""), asked.single())
        assertTrue(called.isEmpty())
    }

    @Test
    fun allowedMutatingToolCallsTheAppWithUnprefixedName() = runBlocking {
        val asked = mutableListOf<GateRequest>(); val called = mutableListOf<String>()
        val out = router(true, asked, called).run(ToolCall("t3", "buckt__log_transaction", "{}"))
        assertFalse(out.isError)
        assertEquals(listOf("buckt-handle:log_transaction"), called)
    }

    @Test
    fun unknownToolIsAnErrorResult() = runBlocking {
        val asked = mutableListOf<GateRequest>(); val called = mutableListOf<String>()
        val out = router(true, asked, called).run(ToolCall("t4", "cleo__start_timer", "{}"))
        assertTrue(out.isError)
        assertTrue(called.isEmpty())
    }
}
