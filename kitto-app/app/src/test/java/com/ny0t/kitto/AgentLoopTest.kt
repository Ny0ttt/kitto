package com.ny0t.kitto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plays back scripted turns and records what the loop sent back. */
private class FakeConversation(private val turns: MutableList<ModelTurn>) : Conversation {
    val events = mutableListOf<String>()
    var turnsRequested = 0
    private var rolledBack = false

    override fun addUserText(text: String) { events += "user:$text" }

    override suspend fun nextTurn(): ModelTurn {
        turnsRequested++
        val next = turns.removeFirst()
        events += "turn:${next.stop}"
        return next
    }

    override fun addToolResults(results: List<ToolOutcome>) {
        events += "results:" + results.joinToString(",") { "${it.toolUseId}=${it.isError}" }
    }

    override fun rollbackQuestion() { rolledBack = true; events += "rollback" }
    fun wasRolledBack() = rolledBack
}

private fun text(stop: StopKind, text: String) = ModelTurn(stop, text, emptyList(), 25, 12)

private fun toolTurn(vararg calls: ToolCall) =
    ModelTurn(StopKind.TOOL_USE, "", calls.toList(), 30, 8)

private val balances = ToolCall("t1", "buckt__get_budget_balances", "{}")

private val okTool: suspend (ToolCall) -> ToolOutcome = { call ->
    ToolOutcome(call.id, """{"remaining":972}""", isError = false)
}

class AgentLoopTest {

    @Test
    fun endTurnReturnsText() = runBlocking {
        val conv = FakeConversation(mutableListOf(text(StopKind.END_TURN, "Hi Ny0t")))
        val answer = AgentLoop(conv, okTool).ask("hi")
        assertEquals("Hi Ny0t", answer)
        assertEquals(listOf("user:hi", "turn:END_TURN"), conv.events)
    }

    @Test
    fun oneToolRoundSendsAllResultsTogether() = runBlocking {
        val conv = FakeConversation(mutableListOf(
            toolTurn(balances, ToolCall("t2", "buckt__get_recent_transactions", "{}")),
            text(StopKind.END_TURN, "You have RM 972 left."),
        ))
        val answer = AgentLoop(conv, okTool).ask("what's left?")
        assertEquals("You have RM 972 left.", answer)
        assertEquals(
            listOf("user:what's left?", "turn:TOOL_USE", "results:t1=false,t2=false", "turn:END_TURN"),
            conv.events,
        )
    }

    @Test
    fun failingToolBecomesErrorResultNotCrash() = runBlocking {
        val failing: suspend (ToolCall) -> ToolOutcome = { throw IllegalStateException("Buckt timed out") }
        val conv = FakeConversation(mutableListOf(
            toolTurn(balances),
            text(StopKind.END_TURN, "Buckt did not answer."),
        ))
        val answer = AgentLoop(conv, failing).ask("balance?")
        assertEquals("Buckt did not answer.", answer)
        assertTrue(conv.events.contains("results:t1=true"))
    }

    @Test
    fun giveUpAfterFiveToolRoundsAndRollsBack() = runBlocking {
        val turns = MutableList(10) { toolTurn(balances) }
        val conv = FakeConversation(turns)
        val answer = AgentLoop(conv, okTool).ask("loop forever")
        assertEquals(GIVE_UP_MESSAGE, answer)
        assertEquals(MAX_TOOL_ROUNDS + 1, conv.turnsRequested)
        assertTrue(conv.wasRolledBack())
    }

    @Test
    fun refusalShowsDeclinedMessage() = runBlocking {
        val conv = FakeConversation(mutableListOf(text(StopKind.REFUSAL, "")))
        assertEquals(DECLINED_MESSAGE, AgentLoop(conv, okTool).ask("x"))
    }

    @Test
    fun maxTokensKeepsTextAndAddsCutOffNote() = runBlocking {
        val conv = FakeConversation(mutableListOf(text(StopKind.MAX_TOKENS, "partial answer")))
        val answer = AgentLoop(conv, okTool).ask("x")
        assertEquals("partial answer\n\n$CUT_OFF_NOTE", answer)
    }

    @Test
    fun cutOffToolRequestIsRolledBack() = runBlocking {
        val cutOff = ModelTurn(StopKind.MAX_TOKENS, "", listOf(balances), 30, 16000)
        val conv = FakeConversation(mutableListOf(cutOff))
        val answer = AgentLoop(conv, okTool).ask("x")
        assertEquals(CUT_OFF_NOTE, answer)
        assertTrue(conv.wasRolledBack())
    }

    @Test
    fun plainCutOffTextIsKept() = runBlocking {
        val conv = FakeConversation(mutableListOf(text(StopKind.MAX_TOKENS, "partial")))
        AgentLoop(conv, okTool).ask("x")
        assertFalse(conv.wasRolledBack())
    }

    @Test
    fun cancellationIsNotTurnedIntoAToolError() = runBlocking {
        val cancelled: suspend (ToolCall) -> ToolOutcome = { throw CancellationException("screen closed") }
        val conv = FakeConversation(mutableListOf(toolTurn(balances), text(StopKind.END_TURN, "never")))
        val thrown = runCatching { AgentLoop(conv, cancelled).ask("x") }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertTrue(conv.wasRolledBack())
        assertFalse(conv.events.any { it.startsWith("results:") })
    }

    @Test
    fun apiFailureRollsBackAndRethrows() = runBlocking {
        val conv = object : Conversation {
            var rolledBack = false
            override fun addUserText(text: String) {}
            override suspend fun nextTurn(): ModelTurn = throw java.io.IOException("no internet")
            override fun addToolResults(results: List<ToolOutcome>) {}
            override fun rollbackQuestion() { rolledBack = true }
        }
        val thrown = runCatching { AgentLoop(conv, okTool).ask("x") }.exceptionOrNull()
        assertTrue(thrown is java.io.IOException)
        assertTrue(conv.rolledBack)
    }

    @Test
    fun logsUsagePerTurn() = runBlocking {
        val logs = mutableListOf<String>()
        val conv = FakeConversation(mutableListOf(text(StopKind.END_TURN, "ok")))
        AgentLoop(conv, okTool, log = { logs += it }).ask("x")
        assertEquals("stop=END_TURN in=25 out=12 tools=0", logs.single())
    }
}
