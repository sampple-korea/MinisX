package com.minisx.app.assistant

import com.minisx.app.assistant.NotificationReply.ActionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationReplyTest {

    @Test fun `the only action that takes text is the reply`() {
        assertEquals(1, NotificationReply.pick(listOf(ActionInfo("읽음", false), ActionInfo("답장", true))))
    }

    @Test fun `with several text actions the one titled reply wins`() {
        val actions = listOf(ActionInfo("Quick note", true), ActionInfo("Reply", true))
        assertEquals(1, NotificationReply.pick(actions))
    }

    @Test fun `an ambiguous or missing reply action is null`() {
        assertNull(NotificationReply.pick(listOf(ActionInfo("A", true), ActionInfo("B", true))))
        assertNull(NotificationReply.pick(listOf(ActionInfo("읽음", false))))
        assertNull(NotificationReply.pick(emptyList()))
    }

    @Test fun `a chosen index must take text`() {
        val actions = listOf(ActionInfo("읽음", false), ActionInfo("답장", true))
        assertEquals(1, NotificationReply.pick(actions, wanted = 1))
        assertNull(NotificationReply.pick(actions, wanted = 0))
        assertNull(NotificationReply.pick(actions, wanted = 5))
    }
}

class AssistantAnswerTest {
    private fun i(role: String, text: String = "", error: String? = null) = AssistantAnswer.Item(role, text, error)

    @Test fun `the reply after the last user message is shown`() {
        val items = listOf(i("user", "a"), i("assistant", "old"), i("user", "손전등 켜줘"), i("assistant", "손전등을 켰어요."))
        assertEquals("손전등을 켰어요.", AssistantAnswer.latest(items))
    }

    @Test fun `an earlier reply is not shown for a new question`() {
        val items = listOf(i("user", "a"), i("assistant", "old answer"), i("user", "b"))
        assertNull(AssistantAnswer.latest(items))
    }

    @Test fun `empty assistant bubbles are skipped and the newest text wins`() {
        val items = listOf(i("user", "q"), i("assistant", "first part"), i("assistant", ""), i("assistant", "  final  "))
        assertEquals("final", AssistantAnswer.latest(items))
    }

    @Test fun `an error is shown when there is no text`() {
        val items = listOf(i("user", "q"), i("assistant", "", error = "rate limited"))
        assertEquals("rate limited", AssistantAnswer.latest(items))
    }

    @Test fun `working while streaming or just after sending`() {
        assertEquals(true, AssistantAnswer.working(streaming = true, haveText = true, sinceSubmitMs = 99_999))
        assertEquals(true, AssistantAnswer.working(streaming = false, haveText = false, sinceSubmitMs = 1_000))
        assertEquals(false, AssistantAnswer.working(streaming = false, haveText = false, sinceSubmitMs = 9_000))
        assertEquals(false, AssistantAnswer.working(streaming = false, haveText = true, sinceSubmitMs = 100))
    }

    @Test fun `both hosts show the overlay, not the full app, unless the user chose full screen`() {
        val session = com.minisx.app.ProductionSources.read("assistant/AssistantSession.kt")
        assertEquals(true, session.contains("override fun onCreateContentView"))
        assertEquals(true, session.contains("AssistantSettings.fullScreen"))
        val activity = com.minisx.app.ProductionSources.read("assistant/AssistActivity.kt")
        assertEquals(true, activity.contains("AssistantOverlay(this)"))
    }

    @Test fun `the overlay hands over to the app for dialogs it cannot show`() {
        val runner = com.minisx.app.ProductionSources.read("assistant/AssistantRunner.kt")
        assertEquals(true, runner.contains("pendingRequest") && runner.contains("pendingSettingsGate") && runner.contains("pendingAndroidPermission"))
    }
}
