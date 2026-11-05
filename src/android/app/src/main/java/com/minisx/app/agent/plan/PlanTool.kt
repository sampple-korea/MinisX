package com.minisx.app.agent.plan

import com.minisx.app.data.model.AgentSchemaNode
import com.minisx.app.data.model.AgentToolDefinition
import com.minisx.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * `update_plan`, the planning tool of the Codex agent: the model keeps a short checklist of the
 * steps of a bigger task and re-sends it as steps finish. The call does nothing but record the
 * plan and echo it back as a checklist, so the transcript, the tool card and the live notification
 * show where the work stands.
 */
object PlanTool {
    const val TOOL_NAME = "update_plan"

    enum class Status(val wire: String, val mark: String) {
        PENDING("pending", "[ ]"),
        IN_PROGRESS("in_progress", "[>]"),
        COMPLETED("completed", "[x]");

        companion object {
            fun of(wire: String): Status? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Step(val text: String, val status: Status)
    data class Plan(val explanation: String?, val steps: List<Step>) {
        /** The step being worked on, else the first one still pending, else null when all are done. */
        val current: Step? get() = steps.firstOrNull { it.status == Status.IN_PROGRESS } ?: steps.firstOrNull { it.status == Status.PENDING }
        val done: Int get() = steps.count { it.status == Status.COMPLETED }
    }

    sealed class Parsed {
        data class Ok(val plan: Plan) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    private const val DESCRIPTION =
        "Keeps a short checklist for a multi-step task. Send the whole plan every time: each step is a short sentence " +
            "with a status (pending, in_progress, completed). Keep exactly one step in_progress until everything is done, " +
            "and update the plan after finishing a step. Skip it for simple tasks, and never make a one-step plan."

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_NAME,
        description = DESCRIPTION,
        parameters = mapOf(
            "explanation" to AgentToolParam("string", "Optional: why the plan changed."),
            "plan" to AgentToolParam(
                "array",
                "Required. The steps, in order.",
                schema = AgentSchemaNode.Arr(
                    items = AgentSchemaNode.Obj(
                        properties = listOf(
                            "step" to AgentSchemaNode.Str("One short sentence."),
                            "status" to AgentSchemaNode.Str("pending, in_progress or completed.", listOf("pending", "in_progress", "completed")),
                        ),
                        required = listOf("step", "status"),
                    ),
                    minItems = 1,
                ),
            ),
        ),
        required = listOf("plan"),
        propertyOrdering = listOf("explanation", "plan"),
    )

    /** The line added to the system prompt while the tool exists (rules from Codex's own prompt). */
    val PROMPT_SECTION: String = """
Plan tool:
- Use update_plan for work with several steps; skip it for straightforward tasks (roughly the easiest quarter).
- Never make a single-step plan.
- After you finish a step, update the plan: mark it completed and the next one in_progress. Keep exactly one step in_progress until all are completed.
""".trimIndent()

    fun parse(args: JSONObject): Parsed {
        val arr = args.optJSONArray("plan") ?: return Parsed.Invalid("`plan` must be an array of {step, status}.")
        if (arr.length() == 0) return Parsed.Invalid("`plan` is empty.")
        val steps = ArrayList<Step>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return Parsed.Invalid("Step ${i + 1} is not an object.")
            val text = o.optString("step", "").trim()
            if (text.isEmpty()) return Parsed.Invalid("Step ${i + 1} has no text.")
            val status = Status.of(o.optString("status", "").trim())
                ?: return Parsed.Invalid("Step ${i + 1} has an unknown status (use pending, in_progress or completed).")
            steps += Step(text, status)
        }
        if (steps.count { it.status == Status.IN_PROGRESS } > 1) {
            return Parsed.Invalid("Only one step may be in_progress at a time.")
        }
        val explanation = args.optString("explanation", "").trim().takeIf { it.isNotEmpty() && it != "null" }
        return Parsed.Ok(Plan(explanation, steps))
    }

    /** What the tool returns: the plan as a checklist, readable in the tool card and in the transcript. */
    fun render(plan: Plan): String = buildString {
        append("Plan updated (").append(plan.done).append('/').append(plan.steps.size).append(" done)")
        plan.explanation?.let { append(": ").append(it) }
        for (s in plan.steps) append('\n').append(s.status.mark).append(' ').append(s.text)
    }
}
