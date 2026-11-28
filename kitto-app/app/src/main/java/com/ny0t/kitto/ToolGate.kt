package com.ny0t.kitto

import com.ny0t.kitto.protocol.ToolSpec

// Claude never sees the Allow/Deny dialog, so the result has to say what happened (a bare "declined"
// contradicted the chat in the M3 device test and Claude offered to retry).
const val DECLINED_BY_USER =
    "Ny0t tapped Deny on the confirmation dialog, so this action was not run and nothing was changed."

/** What the confirmation dialog shows. */
data class GateRequest(val appLabel: String, val toolName: String, val argsJson: String)

/** Read-only tools pass. Anything else (including a missing readOnly flag) asks the user first. */
class ToolGate(private val askUser: suspend (GateRequest) -> Boolean) {
    suspend fun allows(spec: ToolSpec, request: GateRequest): Boolean =
        spec.readOnly || askUser(request)
}

/** One tool Claude can call, and which app answers it. [P] is the app handle (Provider on device). */
data class RoutedTool<P>(val provider: P, val appLabel: String, val spec: ToolSpec)

/**
 * Turns a Claude tool call into a provider call: look up the prefixed name,
 * pass the gate, then call the app with the tool's own (unprefixed) name.
 */
class ToolRouter<P>(
    private val tools: Map<String, RoutedTool<P>>,
    private val gate: ToolGate,
    private val now: () -> Long = System::currentTimeMillis,
    private val callApp: suspend (provider: P, toolName: String, argsJson: String) -> String,
) {
    suspend fun run(call: ToolCall): ToolOutcome {
        val routed = tools[call.name]
            ?: return ToolOutcome(call.id, "Unknown tool: ${call.name}", isError = true)
        val request = GateRequest(routed.appLabel, routed.spec.name, call.argsJson)
        if (!gate.allows(routed.spec, request)) {
            return ToolOutcome(call.id, DECLINED_BY_USER, isError = true)
        }
        // A write goes out with a ticket for this tap; Buckt/Cleo refuse writes without one (M6 C).
        val args = if (routed.spec.readOnly) call.argsJson else withTicket(call.argsJson, call.id, now())
        val result = callApp(routed.provider, routed.spec.name, args)
        return ToolOutcome(call.id, result, isError = false)
    }
}

/** The argument field Buckt and Cleo look for on write tools. They remove it before checking the arguments. */
const val TICKET_FIELD = "_kitto_confirm"

/**
 * Puts `"_kitto_confirm":{"id":<tool_use id>,"at":<tap time, epoch ms>}` first in the arguments object (M6 C).
 * Proves to the app that this exact tool call passed the Allow dialog: one ticket per tap, valid 2 minutes.
 * Plain string work: org.json is a stub in JVM unit tests. Arguments that aren't an object are sent
 * unchanged, and the app refuses them (no ticket / bad arguments).
 */
internal fun withTicket(argsJson: String, toolUseId: String, tappedAt: Long): String {
    val body = argsJson.trim()
    if (!body.startsWith("{") || !body.endsWith("}")) return argsJson
    val ticket = "\"$TICKET_FIELD\":{\"id\":${jsonString(toolUseId)},\"at\":$tappedAt}"
    val rest = body.substring(1).trimStart()
    return if (rest == "}") "{$ticket}" else "{$ticket,$rest"
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
    append('"')
}
