package com.ny0t.kitto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

const val MAX_TOOL_ROUNDS = 5
const val GIVE_UP_MESSAGE = "I couldn't finish this one."
const val DECLINED_MESSAGE = "Claude declined this request."
const val CUT_OFF_NOTE = "(reply was cut off)"

/** The parts of a model reply the loop cares about, free of SDK types. */
enum class StopKind { END_TURN, TOOL_USE, REFUSAL, MAX_TOKENS, OTHER }

data class ToolCall(val id: String, val name: String, val argsJson: String)

data class ModelTurn(
    val stop: StopKind,
    val text: String,
    val toolCalls: List<ToolCall>,
    val inputTokens: Long,
    val outputTokens: Long,
)

data class ToolOutcome(val toolUseId: String, val content: String, val isError: Boolean)

/**
 * The growing chat history. The real one is [SdkConversation]; tests use a fake.
 * Assistant replies are stored exactly as returned, so tool_use ids stay valid.
 */
interface Conversation {
    /** Starts a new question. Remembers where it begins so it can be rolled back. */
    fun addUserText(text: String)

    /** Sends the history and stores the assistant reply as returned. */
    suspend fun nextTurn(): ModelTurn

    fun addToolResults(results: List<ToolOutcome>)

    /** Removes everything since the last [addUserText], so no tool_use is left unanswered. */
    fun rollbackQuestion()
}

class AgentLoop(
    private val conversation: Conversation,
    private val runTool: suspend (ToolCall) -> ToolOutcome,
    private val log: (String) -> Unit = {},
) {
    /** Answers one question, running tools as Claude asks. Returns text for the screen. */
    suspend fun ask(question: String): String {
        conversation.addUserText(question)
        return try {
            runTurns()
        } catch (e: Exception) {
            // A half-finished question would leave the history invalid for the next one.
            conversation.rollbackQuestion()
            throw e
        }
    }

    private suspend fun runTurns(): String {
        var toolRounds = 0
        while (true) {
            val turn = conversation.nextTurn()
            log("stop=${turn.stop} in=${turn.inputTokens} out=${turn.outputTokens} tools=${turn.toolCalls.size}")
            // A tool request that won't be answered (cut off, odd stop) would make every later
            // request invalid, so drop this question from the history.
            if (turn.stop != StopKind.TOOL_USE && turn.toolCalls.isNotEmpty()) {
                conversation.rollbackQuestion()
            }
            when (turn.stop) {
                StopKind.END_TURN -> return turn.text
                StopKind.REFUSAL -> return DECLINED_MESSAGE
                StopKind.MAX_TOKENS -> return listOf(turn.text, CUT_OFF_NOTE).filter { it.isNotBlank() }.joinToString("\n\n")
                StopKind.OTHER -> return "Unexpected reply from Claude (stop=${turn.stop})."
                StopKind.TOOL_USE -> {
                    if (turn.toolCalls.isEmpty()) return "Unexpected reply from Claude (no tool calls)."
                    if (toolRounds >= MAX_TOOL_ROUNDS) {
                        conversation.rollbackQuestion()
                        return GIVE_UP_MESSAGE
                    }
                    toolRounds++
                    val results = turn.toolCalls.map { call -> runSafely(call) }
                    conversation.addToolResults(results)
                }
            }
        }
    }

    /** A failing tool becomes an error result for Claude, never an exception that kills the answer. */
    private suspend fun runSafely(call: ToolCall): ToolOutcome =
        try {
            runTool(call)
        } catch (e: TimeoutCancellationException) {
            // Our own 20 s provider timeout: report it to Claude like any tool failure.
            log("tool ${call.name} timed out")
            ToolOutcome(call.id, "${call.name} timed out", isError = true)
        } catch (e: CancellationException) {
            throw e // real cancellation (screen closed): let it stop the loop
        } catch (e: Exception) {
            log("tool ${call.name} failed: ${e.message}")
            ToolOutcome(call.id, e.message ?: e::class.simpleName.orEmpty(), isError = true)
        }
}
