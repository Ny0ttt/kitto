package com.ny0t.kitto

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.ny0t.kitto.protocol.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

const val CLAUDE_MODEL = "claude-haiku-5-5"

/** What the screen hears while a reply is still arriving. */
sealed interface StreamUpdate {
    /** Claude started a tool call; [claudeName] is the prefixed tool name (e.g. buckt__get_budget_balances). */
    data class ToolStarted(val claudeName: String) : StreamUpdate

    /** A piece of reply text. Pieces from one turn join up into that turn's text. */
    data class Text(val piece: String) : StreamUpdate
}

/**
 * Real [Conversation] backed by the Anthropic SDK. Keeps the history as SDK
 * messages; assistant replies are appended with Message.toParam() as returned.
 */
class SdkConversation(
    private val client: AnthropicClient,
    /**
     * Must stay byte-identical for the whole conversation: Haiku 5.5's thinking blocks are sealed to the
     * system prompt, tools and earlier messages, and a changed prefix makes the API reject every replayed
     * block (400 "bound to a different conversation"). Anything that changes goes in a new user message.
     */
    private val system: String,
    private val tools: List<ToolUnion>,
    /** Called for each [StreamUpdate] as the reply arrives. Runs on a background thread. */
    private val onUpdate: (StreamUpdate) -> Unit = {},
) : Conversation {

    private val history = mutableListOf<MessageParam>()
    private var questionStart = 0

    override fun addUserText(text: String) {
        questionStart = history.size
        history += MessageParam.ofUser(text)
    }

    override suspend fun nextTurn(): ModelTurn {
        val params = MessageCreateParams.builder()
            .model(CLAUDE_MODEL)
            .maxTokens(16000L)
            .system(system)
            .tools(tools)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .messages(history.toList())
            .build()
        // Streaming network call: keep it off the main thread. The accumulator rebuilds the same
        // Message that create() returned, so the loop and the history don't change.
        val message = withContext(Dispatchers.IO) {
            val accumulator = MessageAccumulator.create()
            // use{} closes the stream even on error or cancel; ensureActive() stops at the next
            // event once the screen is gone.
            client.messages().createStreaming(params).use { stream ->
                stream.stream().forEach { event ->
                    ensureActive()
                    accumulator.accumulate(event)
                    event.contentBlockStart()
                        .flatMap { it.contentBlock().toolUse() }
                        .ifPresent { onUpdate(StreamUpdate.ToolStarted(it.name())) }
                    event.contentBlockDelta()
                        .flatMap { it.delta().text() }
                        .ifPresent { onUpdate(StreamUpdate.Text(it.text())) }
                }
            }
            accumulator.message()
        }
        history += message.toParam()
        return message.toModelTurn()
    }

    override fun addToolResults(results: List<ToolOutcome>) {
        history += MessageParam.ofUserOfContentBlockParams(
            results.map { r ->
                ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(r.toolUseId)
                        .content(r.content)
                        .isError(r.isError)
                        .build()
                )
            }
        )
    }

    override fun rollbackQuestion() {
        history.subList(questionStart, history.size).clear()
    }
}

/** Maps an SDK reply to the loop's ModelTurn. The stop reason is compared as its wire string. */
internal fun Message.toModelTurn(): ModelTurn {
    val stop = when (stopReason().map { it.asString() }.orElse(null)) {
        "end_turn" -> StopKind.END_TURN
        "tool_use" -> StopKind.TOOL_USE
        "refusal" -> StopKind.REFUSAL
        "max_tokens" -> StopKind.MAX_TOKENS
        else -> StopKind.OTHER
    }
    val text = content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
    val calls = content().mapNotNull { block -> block.toolUse().orElse(null) }.map { use ->
        ToolCall(
            id = use.id(),
            name = use.name(),
            argsJson = JSONObject(use._input().convert(Map::class.java)).toString(),
        )
    }
    return ModelTurn(stop, text, calls, usage().inputTokens(), usage().outputTokens())
}

/**
 * Builds Claude tool definitions from provider specs. [entries] pairs each
 * prefixed Claude name (from toClaudeName) with its spec from the provider.
 */
fun buildToolUnions(entries: List<Pair<String, ToolSpec>>): List<ToolUnion> =
    entries.map { (claudeName, spec) ->
        ToolUnion.ofTool(
            Tool.builder()
                .name(claudeName)
                .description(spec.description)
                .inputSchema(schemaFrom(spec.parameters))
                .build()
        )
    }

/** Plain Kotlin Maps/Lists from org.json, so the SDK can take them as JsonValue. */
internal fun jsonToKotlin(v: Any?): Any? = when (v) {
    is JSONObject -> buildMap {
        val keys = v.keys()
        while (keys.hasNext()) {
            val key = keys.next() as String
            put(key, jsonToKotlin(v.get(key)))
        }
    }
    is JSONArray -> List(v.length()) { jsonToKotlin(v.get(it)) }
    JSONObject.NULL -> null
    else -> v
}

/** Turns a provider's JSON Schema (properties + required) into the SDK's input schema. */
internal fun schemaFrom(parametersJson: String): Tool.InputSchema {
    val root = jsonToKotlin(JSONObject(parametersJson)) as Map<*, *>
    val props = Tool.InputSchema.Properties.builder()
    (root["properties"] as? Map<*, *>)?.forEach { (key, value) ->
        props.putAdditionalProperty(key as String, JsonValue.from(value))
    }
    val schema = Tool.InputSchema.builder().properties(props.build())
    (root["required"] as? List<*>)?.let { schema.required(it.map { r -> r as String }) }
    return schema.build()
}
