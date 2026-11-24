package com.ny0t.kitto.protocol

import org.json.JSONArray

/** One tool an app offers, as described by its listTools() JSON. */
data class ToolSpec(
    val name: String,
    val description: String,
    /** Missing in the JSON = false (treated as mutating, so it goes behind the gate). */
    val readOnly: Boolean,
    /** The JSON Schema for the arguments, kept as raw JSON text to pass to Claude as-is. */
    val parameters: String,
)

/** Parses the JSON array returned by IAgentTools.listTools(). */
fun parseToolSpecs(json: String): List<ToolSpec> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        ToolSpec(
            name = obj.getString("name"),
            description = obj.getString("description"),
            readOnly = obj.optBoolean("readOnly", false),
            parameters = obj.getJSONObject("parameters").toString(),
        )
    }
}
