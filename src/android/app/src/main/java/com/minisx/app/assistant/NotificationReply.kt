package com.minisx.app.assistant

/**
 * Which action of a notification takes a typed reply (a chat app's "Reply" button). Pure, so the
 * choice is tested on the JVM.
 */
object NotificationReply {

    data class ActionInfo(val title: String, val acceptsText: Boolean)

    /**
     * The index of the action to fill in: [wanted] when given and it takes text, else the only
     * action that takes text, else the one titled like "Reply"/"답장"; null if none fits or the
     * choice is ambiguous.
     */
    fun pick(actions: List<ActionInfo>, wanted: Int? = null): Int? {
        if (wanted != null) return wanted.takeIf { it in actions.indices && actions[it].acceptsText }
        val withText = actions.indices.filter { actions[it].acceptsText }
        if (withText.size == 1) return withText[0]
        val titled = withText.filter { Regex("reply|답장|응답|respond", RegexOption.IGNORE_CASE).containsMatchIn(actions[it].title) }
        return titled.singleOrNull()
    }
}
