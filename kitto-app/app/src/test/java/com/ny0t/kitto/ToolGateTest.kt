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

    private val sentArgs = mutableListOf<String>()

    private fun router(userSays: Boolean, asked: MutableList<GateRequest>, called: MutableList<String>) =
        ToolRouter(
            tools,
            ToolGate { request -> asked += request; userSays },
            now = { 1_000L },
        ) { provider, toolName, args -> called += "$provider:$toolName"; sentArgs += args; """{"ok":true}""" }

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

    // M6 C: the confirmation ticket

    @Test
    fun allowedWriteCarriesATicketForThisCall() = runBlocking {
        router(true, mutableListOf(), mutableListOf())
            .run(ToolCall("toolu_1", "buckt__log_transaction", """{"amount":5}"""))
        assertEquals("""{"_kitto_confirm":{"id":"toolu_1","at":1000},"amount":5}""", sentArgs.single())
    }

    @Test
    fun dialogShowsTheArgumentsWithoutTheTicket() = runBlocking {
        val asked = mutableListOf<GateRequest>()
        router(true, asked, mutableListOf()).run(ToolCall("toolu_1", "buckt__log_transaction", """{"amount":5}"""))
        assertEquals("""{"amount":5}""", asked.single().argsJson)
    }

    @Test
    fun readToolGetsNoTicket() = runBlocking {
        router(true, mutableListOf(), mutableListOf()).run(ToolCall("toolu_2", "buckt__get_budget_balances", "{}"))
        assertEquals("{}", sentArgs.single())
    }

    @Test
    fun deniedWriteSendsNothing() = runBlocking {
        router(false, mutableListOf(), mutableListOf()).run(ToolCall("toolu_3", "buckt__log_transaction", "{}"))
        assertTrue(sentArgs.isEmpty())
    }

    @Test
    fun ticketInEmptyArgs() {
        assertEquals("""{"_kitto_confirm":{"id":"t","at":7}}""", withTicket(" { } ", "t", 7))
    }

    @Test
    fun ticketIdIsEscaped() {
        assertEquals("""{"_kitto_confirm":{"id":"a\"b\\c","at":7}}""", withTicket("{}", "a\"b\\c", 7))
    }

    @Test
    fun nonObjectArgsAreLeftAlone() {
        assertEquals("[1]", withTicket("[1]", "t", 7))
        assertEquals("", withTicket("", "t", 7))
    }
}
