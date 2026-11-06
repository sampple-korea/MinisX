package com.minisx.app.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds pending deep-link side-effects that outlive a single navigation event.
 * Mirrors iOS DeepLinkCoordinator.
 */
object DeepLinkCoordinator {

    data class EnvVarCreate(val key: String, val value: String, val note: String)

    private val _pendingEnvVarCreate = MutableStateFlow<EnvVarCreate?>(null)
    val pendingEnvVarCreate: StateFlow<EnvVarCreate?> = _pendingEnvVarCreate.asStateFlow()

    fun setPendingEnvVarCreate(key: String, value: String, note: String = "") {
        _pendingEnvVarCreate.value = EnvVarCreate(key, value, note)
    }

    fun consumePendingEnvVarCreate(): EnvVarCreate? {
        val current = _pendingEnvVarCreate.value
        _pendingEnvVarCreate.value = null
        return current
    }

    /**
     * Optional `?tab=…` hint from `minis://settings/logs?tab=config-audit`.
     * The Logs screen reads this on appear to land on the right
     * segmented-control tab. Cleared by the screen after consumption.
     * Mirrors iOS DeepLinkCoordinator.pendingLogsTab.
     */
    private val _pendingLogsTab = MutableStateFlow<String?>(null)
    val pendingLogsTab: StateFlow<String?> = _pendingLogsTab.asStateFlow()

    fun setPendingLogsTab(tab: String?) { _pendingLogsTab.value = tab }
    fun consumePendingLogsTab(): String? {
        val current = _pendingLogsTab.value
        _pendingLogsTab.value = null
        return current
    }

    /**
     * Pending pinned-shortcut HTML preview: filesystem path + cached title.
     * MainActivity sets this on `minis://preview/html` deep link; ChatScreen
     * reads it on first composition and routes into WebPreviewFullscreen.
     */
    data class HtmlPreview(val sessionId: String, val resourcePath: String, val title: String)

    private val _pendingHtmlPreview = MutableStateFlow<HtmlPreview?>(null)
    val pendingHtmlPreview: StateFlow<HtmlPreview?> = _pendingHtmlPreview.asStateFlow()

    fun setPendingHtmlPreview(sessionId: String, resourcePath: String, title: String) {
        _pendingHtmlPreview.value = HtmlPreview(sessionId, resourcePath, title)
    }

    fun consumePendingHtmlPreview(): HtmlPreview? {
        val current = _pendingHtmlPreview.value
        _pendingHtmlPreview.value = null
        return current
    }

    /**
     * App-icon quick-action that a freshly-opened ChatScreen should auto-
     * trigger on first compose. Mirrors iOS `pendingChatAction` on
     * AIChatViewModel. Set by [com.minisx.app.MainActivity] /
     * [com.minisx.app.ui.navigation.AppNavigation] when the launch
     * intent carries `minis://action/voice_chat` or
     * `minis://action/camera_chat`; consumed exactly once by ChatScreen
     * so re-entering the same chat later doesn't fire the action again.
     */
    enum class ChatAction { START_VOICE, OPEN_CAMERA }

    private val _pendingChatAction = MutableStateFlow<ChatAction?>(null)
    val pendingChatAction: StateFlow<ChatAction?> = _pendingChatAction.asStateFlow()

    fun setPendingChatAction(action: ChatAction) {
        _pendingChatAction.value = action
    }

    fun consumePendingChatAction(): ChatAction? {
        val current = _pendingChatAction.value
        _pendingChatAction.value = null
        return current
    }

    /**
     * [T-android-ask-deeplink] Text for a new chat's input box, from
     * `minis://action/ask?prompt=…` (Tasker, a home-screen link, another app).
     *
     * Deliberately PREFILL ONLY. A deep link can be fired by any app or web
     * page, and a sent prompt is an agent turn that can use the shell and the
     * device tools; the user reviews the text and taps Send themselves. (iOS
     * AskMinisIntent sends because Siri / Shortcuts are the user's own
     * invocation, a different trust model.)
     */
    const val MAX_PREFILL_CHARS = 8_000
    private val _pendingPrompt = MutableStateFlow<String?>(null)

    fun setPendingPrompt(prompt: String?) {
        _pendingPrompt.value = prompt?.trim()?.take(MAX_PREFILL_CHARS)?.takeIf { it.isNotEmpty() }
    }

    fun consumePendingPrompt(): String? {
        val current = _pendingPrompt.value
        _pendingPrompt.value = null
        return current
    }

    /**
     * An assistant invocation (power-button long press etc.) for the new chat that is opening. Set only
     * by MainActivity after redeeming the one-time token the assistant session registered in this
     * process; there is no URL for it (see AssistantInvocations).
     */
    private val _pendingAssistant = MutableStateFlow<com.minisx.app.assistant.AssistantInvocation?>(null)

    fun setPendingAssistant(invocation: com.minisx.app.assistant.AssistantInvocation?) { _pendingAssistant.value = invocation }

    fun consumePendingAssistant(): com.minisx.app.assistant.AssistantInvocation? {
        val current = _pendingAssistant.value
        _pendingAssistant.value = null
        return current
    }
}
