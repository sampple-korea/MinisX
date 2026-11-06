package com.minisx.app.tools

import com.minisx.app.data.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Execution of memory_write and memory_get. The tool definitions the model sees are in
 * [AgentTools]; the store behind them is [MemoryRepository] / [com.minisx.app.agent.memory.SavedMemories].
 */
object MemoryTools {

    // -- Execution --

    data class ToolResult(
        val output: String,
        val success: Boolean,
        val toolTitle: String = "",
    )

    /**
     * memory_write: `action` add (default), update or delete a saved memory; `content` is the fact (add,
     * update), `id` names the memory (update, delete; several separated by commas, or `all`).
     */
    fun executeMemoryWrite(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val toolTitle = obj.optString("tool_title", "memory_write")
            val action = obj.optString("action", "add")
            val content = obj.optString("content", "")
            val id = obj.optString("id", "")
            if (action.isBlank() || action.trim().lowercase() in setOf("add", "save", "create")) {
                if (content.isBlank()) return ToolResult("Error: 'content' is required: one short sentence about the user.", false, toolTitle)
            }
            val outcome = repository.applyMemoryAction(action, content, id)
            ToolResult(outcome.message, outcome.ok, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }

    /** memory_get: no keywords lists the saved memories with ids; keywords search them and the older notes. */
    fun executeMemoryGet(inputJson: String, repository: MemoryRepository): ToolResult {
        return try {
            val obj = JSONObject(inputJson)
            val keywords = obj.optString("keywords", "")
            val scope = obj.optString("scope", "all")
            val toolTitle = obj.optString("tool_title", "memory_get")

            val result = if (keywords.isBlank() && scope != "daily") {
                repository.listSavedMemories() + "\n\n(Search older notes and GLOBAL.md with keywords.)"
            } else {
                repository.getMemory(keywords, scope)
            }
            ToolResult(result, true, toolTitle)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", false)
        }
    }
}
