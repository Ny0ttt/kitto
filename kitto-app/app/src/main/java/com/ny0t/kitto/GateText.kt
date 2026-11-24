package com.ny0t.kitto

import org.json.JSONObject

/**
 * Tool arguments as readable lines for the Allow/Deny dialog, for any tool:
 * {"type":"expense","amount":15,"budgetName":"Food"} → "Type: expense\nAmount: 15\nBudget name: Food".
 * Pure Kotlin so it can be unit-tested (org.json is a stub in JVM tests).
 */
fun formatArgs(args: Map<*, *>): String =
    args.entries.joinToString("\n") { (key, value) -> "${label(key.toString())}: ${show(value)}" }

/** budgetName / budget_name → "Budget name". */
internal fun label(key: String): String {
    val words = key
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .trim()
        .lowercase()
    return words.replaceFirstChar { it.uppercase() }
}

private fun show(value: Any?): String = when (value) {
    null -> "none"
    is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is Float -> show(value.toDouble())
    is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${show(v)}" }
    is List<*> -> value.joinToString(", ") { show(it) }
    else -> value.toString()
}

/** The dialog body. Falls back to the raw JSON if it doesn't parse as an object. */
fun gateText(request: GateRequest): String {
    val args = try {
        jsonToKotlin(JSONObject(request.argsJson)) as Map<*, *>
    } catch (e: Exception) {
        null
    }
    val details = when {
        args == null -> request.argsJson
        args.isEmpty() -> ""
        else -> formatArgs(args)
    }
    val head = "Kitto wants to ${request.toolName} in ${request.appLabel}."
    return if (details.isEmpty()) head else "$head\n\n$details"
}
