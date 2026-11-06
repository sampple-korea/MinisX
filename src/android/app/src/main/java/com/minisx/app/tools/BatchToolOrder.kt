package com.minisx.app.tools

/**
 * [T-batch-read-after-write] Which calls of one tool batch must wait for which
 * (iOS BatchToolOrder, 1eb3e2bef; Windows `writers_before`). A reader
 * (`read_image`, `file_read`) waits for every writer (`shell_execute`,
 * `file_write`, `file_edit`) that precedes it in the batch, because models
 * routinely write a file and read it in the same turn. Nothing else is
 * ordered: writers, browser calls, sub agents and readers with no writer
 * before them still start together.
 */
object BatchToolOrder {
    val WRITERS: Set<String> = setOf("shell_execute", ExecCommandTool.EXEC_NAME, ExecCommandTool.STDIN_NAME, FileWriteTool.NAME, FileEditTool.NAME, ApplyPatchTool.NAME)
    val READERS: Set<String> = setOf(ReadImageTool.NAME, FileReadTool.NAME)

    /** The writers that edit files directly (a shell may do anything, but runs alongside other shells). */
    val FILE_WRITERS: Set<String> = setOf(FileWriteTool.NAME, FileEditTool.NAME, ApplyPatchTool.NAME)

    /** Indices of the writers the call at [index] must wait for (empty unless it is a reader). */
    fun writersBefore(index: Int, names: List<String>): List<Int> {
        if (index !in names.indices || names[index] !in READERS) return emptyList()
        return (0 until index).filter { names[it] in WRITERS }
    }

    /**
     * [T-codex-exclusive-writers] What the call at [index] waits for. Codex runs the tools that change
     * state one at a time, in the order the model wrote them (a shared lock for tools that are safe to
     * overlap, an exclusive one for the rest: `core/src/tools/parallel.rs`), so `mkdir` followed by a
     * file write, or two edits of one file, in one turn behave the way the model meant. Here:
     *  - a reader waits for every earlier writer (as before);
     *  - a file writer waits for every earlier writer, shell included;
     *  - a shell waits for earlier file writers, but not for other shells, which may still run together;
     *  - everything else (browser, sub agents, ...) starts at once.
     */
    fun waitsFor(index: Int, names: List<String>): List<Int> {
        if (index !in names.indices) return emptyList()
        val name = names[index]
        val earlier = 0 until index
        return when (name) {
            in READERS -> earlier.filter { names[it] in WRITERS }
            in FILE_WRITERS -> earlier.filter { names[it] in WRITERS }
            "shell_execute", ExecCommandTool.EXEC_NAME, ExecCommandTool.STDIN_NAME -> earlier.filter { names[it] in FILE_WRITERS }
            else -> emptyList()
        }
    }
}
