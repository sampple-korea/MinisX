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
