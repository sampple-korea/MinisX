package com.minisx.app.agent

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptVariablesTest {
    private val now = ZonedDateTime.of(2026, 11, 5, 9, 30, 0, 0, ZoneId.of("Asia/Seoul"))
    private val v = PromptVariables.values(now, model = "GPT-6.1", device = "Pixel 9", language = "ko-KR")

    @Test fun `known names are filled in`() {
        assertEquals("2026-11-05 09:30 (Thursday, Asia/Seoul)", PromptVariables.apply("{{datetime}} ({{weekday}}, {{timezone}})", v))
        assertEquals("GPT-6.1 on Pixel 9, ko-KR", PromptVariables.apply("{{model}} on {{device}}, {{language}}", v))
    }

    @Test fun `spaces inside the braces and case do not matter`() {
        assertEquals("2026-11-05", PromptVariables.apply("{{ Date }}", v))
    }

    @Test fun `unknown names and text without braces stay as typed`() {
        assertEquals("{{mood}} today", PromptVariables.apply("{{mood}} today", v))
        assertEquals("plain", PromptVariables.apply("plain", v))
    }

    @Test fun `a missing value leaves its placeholder`() {
        val sparse = PromptVariables.values(now, model = null, device = null, language = null)
        assertEquals("{{model}}", PromptVariables.apply("{{model}}", sparse))
    }
}
