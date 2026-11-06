package com.minisx.app.agent.applypatch

/**
 * `apply_patch`, the file editing format Codex's models are trained on:
 *
 * ```
 * *** Begin Patch
 * *** Add File: path
 * +every line of the new file, prefixed with +
 * *** Update File: path
 * *** Move to: new/path          (optional)
 * @@ optional line of context to find the place
 *  unchanged line
 * -removed line
 * +added line
 * *** Delete File: path
 * *** End Patch
 * ```
 *
 * This is a Kotlin port of the grammar and matching rules in Codex's `codex-rs/apply-patch`
 * (`parser.rs`, `streaming_parser.rs`, `seek_sequence.rs`, `file_update.rs`, `text_file.rs`): the same
 * hunk shapes, the same four-step line matching (exact, ignoring trailing space, ignoring surrounding
 * space, ignoring typographic punctuation) and the same line-ending preservation. The files are
 * reached through plain functions, so all of it is unit-tested on the JVM.
 */
sealed class Hunk {
    data class AddFile(val path: String, val contents: String) : Hunk()
    data class DeleteFile(val path: String) : Hunk()
    data class UpdateFile(val path: String, val movePath: String?, val chunks: List<Chunk>) : Hunk()
}

/** One `@@` block of an update: the lines it expects to find and what replaces them. */
data class Chunk(
    /**
     * The text after each `@@`: lines to find, in order, before looking for [oldLines] (a class, then a
     * method in it). Empty for a bare `@@`. Codex's own instructions show two `@@` lines in a row, and
     * its newest parser rejects that; this accepts it.
     */
    val changeContexts: List<String>,
    val oldLines: List<String>,
    val newLines: List<String>,
    /** For every unchanged line: its index in [oldLines] and in [newLines]. */
    val contextIndices: List<Pair<Int, Int>>,
    /** Followed by `*** End of File`: the lines must be the last ones of the file. */
    val isEndOfFile: Boolean,
)

/** The patch is not valid or does not fit the files. The message is what the model is told. */
class PatchException(message: String) : Exception(message)

/** What a patch did, in the order Codex reports it. */
data class PatchSummary(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
) {
    fun text(): String = buildString {
        append("Success. Updated the following files:")
        added.forEach { append("\nA ").append(it) }
        modified.forEach { append("\nM ").append(it) }
        deleted.forEach { append("\nD ").append(it) }
    }
}
