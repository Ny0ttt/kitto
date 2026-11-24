package com.ny0t.kitto

import com.ny0t.kitto.protocol.ToolSpec

const val DECLINED_BY_USER = "The user declined this action."

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
    private val callApp: suspend (provider: P, toolName: String, argsJson: String) -> String,
) {
    suspend fun run(call: ToolCall): ToolOutcome {
        val routed = tools[call.name]
            ?: return ToolOutcome(call.id, "Unknown tool: ${call.name}", isError = true)
        val request = GateRequest(routed.appLabel, routed.spec.name, call.argsJson)
        if (!gate.allows(routed.spec, request)) {
            return ToolOutcome(call.id, DECLINED_BY_USER, isError = true)
        }
        val result = callApp(routed.provider, routed.spec.name, call.argsJson)
        return ToolOutcome(call.id, result, isError = false)
    }
}
