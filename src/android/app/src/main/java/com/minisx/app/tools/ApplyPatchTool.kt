package com.minisx.app.tools

import android.content.Context
import com.minisx.app.agent.applypatch.Hunk
import com.minisx.app.agent.applypatch.PatchApplier
import com.minisx.app.agent.applypatch.PatchException
import com.minisx.app.agent.applypatch.PatchParser
import com.minisx.app.data.ContextOffload
import com.minisx.app.data.model.AgentToolDefinition
import com.minisx.app.data.model.AgentToolParam
import com.minisx.app.logging.AppLogger
import com.minisx.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * `apply_patch`: Codex's file editing tool (see [com.minisx.app.agent.applypatch]). Offered to the
 * model families that are trained on it, next to `file_edit`, which stays for everything else.
 *
 * One call can add, update (even move) and delete several files. The patch is worked out in memory
 * first, so a hunk that does not fit leaves every file as it was.
 */
object ApplyPatchTool {
    const val NAME = "apply_patch"

    /** Relative paths in a patch are taken from here, the sandbox's home. */
    private const val BASE_DIR = "/root"

    /** The model families whose training includes this format: GPT, the o-series and Codex models. */
    fun suits(modelId: String?): Boolean {
        val id = modelId?.lowercase() ?: return false
        return id.contains("gpt") || id.contains("codex") || Regex("(^|[/:._-])o\\d").containsMatchIn(id)
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Edit files with a patch. One call can add, change, move and delete several files; use it for edits " +
            "spread over a file or over several files. If a hunk does not match, nothing is changed.\n\n" +
            "`input` is the whole patch:\n" +
            "*** Begin Patch\n" +
            "[ one or more file sections ]\n" +
            "*** End Patch\n\n" +
            "Each file section starts with one of:\n" +
            "*** Add File: <path> - every following line is the new file's content, each prefixed with +\n" +
            "*** Delete File: <path> - nothing follows\n" +
            "*** Update File: <path> - hunks follow; optionally right after it `*** Move to: <new path>`\n\n" +
            "A hunk starts with @@ (optionally followed by a line that appears just before the change, such as a class or " +
            "function header; two @@ lines in a row narrow it down, e.g. a class, then a method). Then come the lines: a space " +
            "for a line that stays, - for a line to remove, + for a line to add. Show about 3 lines of unchanged context " +
            "before and after each change, enough that the place is unique; do not repeat the file. `*** End of File` after " +
            "a hunk pins it to the end of the file.\n\n" +
            "Example:\n" +
            "*** Begin Patch\n" +
            "*** Update File: /root/app/main.py\n" +
            "@@ def greet():\n" +
            "-    print(\"Hi\")\n" +
            "+    print(\"Hello, world!\")\n" +
            "*** End Patch\n\n" +
            "Paths are absolute Linux paths (relative ones are taken from /root). Read the file first so the lines you quote " +
            "are exactly what is there.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Rename helper and update callers'). Use the same language as the user."),
            "input" to AgentToolParam("string", "The whole patch, from *** Begin Patch to *** End Patch."),
        ),
        required = listOf("tool_title", "input"),
        propertyOrdering = listOf("tool_title", "input"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (e: Exception) {
            return ToolExecutionResult("Error: the arguments are not valid JSON: ${e.message}", false)
        }
        val toolTitle = args.optString("tool_title", NAME)
        val input = args.optString("input", "").ifBlank { args.optString("patch", "") }
        if (input.isBlank()) return ToolExecutionResult("Error: 'input' (the patch) is required", false, toolTitle = toolTitle)

        // [T-offload-placeholder-write-guard] A patch rewritten into the offload stub by history
        // pruning must never be replayed as a real edit.
        if (ContextOffload.isOffloadPlaceholder(input)) {
            AppLogger.warning("ApplyPatch", "REFUSED offload-placeholder patch bytes=${input.length}")
            return ToolExecutionResult(ContextOffload.placeholderWriteRefusal("input", NAME), false, toolTitle = toolTitle)
        }

        return try {
            val hunks = PatchParser.parse(input)
            if (hunks.isEmpty()) return ToolExecutionResult("Error: the patch has no changes", false, toolTitle = toolTitle)

            val hostFiles = HashMap<String, File>()
            fun absolute(path: String) = if (path.startsWith("/")) path else "$BASE_DIR/$path"
            fun host(path: String): File {
                val linux = absolute(path)
                return hostFiles.getOrPut(linux) {
                    PRootKernel.resolveSessionHostPath(sessionId, linux, context)
                        ?: throw PatchException("Cannot resolve path: $path")
                }
            }

            // Every path the patch writes, deletes or moves must be writable before anything is read.
            val touched = hunks.flatMap { h ->
                when (h) {
                    is Hunk.AddFile -> listOf(h.path)
                    is Hunk.DeleteFile -> listOf(h.path)
                    is Hunk.UpdateFile -> listOfNotNull(h.path, h.movePath)
                }
            }
            for (p in touched) {
                if (PRootKernel.isLinuxPathUnderReadOnlyMount(absolute(p))) {
                    return ToolExecutionResult(
                        "Error: ${absolute(p)} is inside a read-only mounted folder and cannot be modified. " +
                            "Nothing was changed.",
                        false, toolTitle = toolTitle,
                    )
                }
            }

            val plan = FileToolIO.run("Applying patch", writes = true) {
                val p = PatchApplier.plan(hunks) { path ->
                    val f = host(path)
                    if (!f.isFile) null
                    else {
                        if (f.length() > FileToolIO.MAX_EDIT_BYTES) {
                            throw PatchException("${absolute(path)} is ${f.length()} bytes; apply_patch handles files up to ${FileToolIO.MAX_EDIT_BYTES} bytes")
                        }
                        f.readText()
                    }
                }
                // Planned without error: now write. The planning read everything it needed.
                for ((path, content) in p.files) {
                    val f = host(path)
                    if (content == null) {
                        f.delete()
                    } else {
                        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        f.writeText(content)
                    }
                }
                p
            }
            // Report the paths the way the model wrote them.
            ToolExecutionResult(plan.summary.text(), true, toolTitle = toolTitle)
        } catch (e: PatchException) {
            ToolExecutionResult("Error: ${e.message}\nNothing was changed.", false, toolTitle = toolTitle)
        } catch (e: FileToolIO.TimedOut) {
            ToolExecutionResult("Error: ${e.message}", false, toolTitle = toolTitle, timedOut = true)
        } catch (e: FileToolIO.OutOfMemory) {
            ToolExecutionResult("Error: ${e.message} Use the shell for large files.", false, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error applying patch: ${e.message}", false, toolTitle = toolTitle)
        }
    }

    /** The first file a patch touches, for the notification and the tool block title. */
    fun firstPath(input: String): String? =
        Regex("^\\*\\*\\* (?:Add|Update|Delete) File: (.+)$", RegexOption.MULTILINE).find(input)?.groupValues?.get(1)?.trim()
}
