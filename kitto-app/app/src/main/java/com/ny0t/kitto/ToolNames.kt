package com.ny0t.kitto

// Claude only accepts tool names matching this pattern (max 64 chars).
private val CLAUDE_NAME = Regex("[a-zA-Z0-9_-]{1,64}")
private const val SEPARATOR = "__"

/**
 * Short id for a provider app, taken from its package name:
 * `com.navitech.buckt` -> `buckt`. Letters and digits only, so the
 * separator `__` can never appear inside a prefix.
 */
fun prefixFor(packageName: String): String {
    val prefix = packageName.substringAfterLast('.').lowercase().filter { it.isLetterOrDigit() }
    require(prefix.isNotEmpty()) { "No usable prefix in package name: $packageName" }
    return prefix
}

/** Builds the name Claude sees, e.g. `buckt__get_budget_balances`. Fails loudly if invalid. */
fun toClaudeName(prefix: String, toolName: String): String {
    val name = "$prefix$SEPARATOR$toolName"
    require(CLAUDE_NAME.matches(name)) { "Tool name not valid for Claude: $name" }
    return name
}

/** Splits a Claude tool name back into (prefix, toolName). Null if there is no separator. */
fun fromClaudeName(claudeName: String): Pair<String, String>? {
    val idx = claudeName.indexOf(SEPARATOR)
    if (idx <= 0) return null
    return claudeName.substring(0, idx) to claudeName.substring(idx + SEPARATOR.length)
}
