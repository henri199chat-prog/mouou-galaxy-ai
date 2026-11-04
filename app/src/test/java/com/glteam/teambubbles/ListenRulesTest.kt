package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenRulesTest {
    @Test fun speechLevelIsClampedTo0And1() {
        assertEquals(0f, ListenRules.level(-10f), 0f)
        assertEquals(1f, ListenRules.level(20f), 0f)
        assertEquals(0.5f, ListenRules.level(4f), 0.01f)
    }

    @Test fun languageErrorsFallBackToArabicOnce() {
        assertTrue(ListenRules.shouldRetryWithFallback(ListenRules.ERROR_LANGUAGE_NOT_SUPPORTED, "ar-TN"))
        assertTrue(ListenRules.shouldRetryWithFallback(ListenRules.ERROR_LANGUAGE_UNAVAILABLE, "ar-TN"))
        assertFalse(ListenRules.shouldRetryWithFallback(ListenRules.ERROR_LANGUAGE_UNAVAILABLE, "ar")) // only once
        assertFalse(ListenRules.shouldRetryWithFallback(7, "ar-TN"))
    }

    @Test fun everyErrorHasADarijaLine() {
        for (e in 1..14) assertTrue(ListenRules.darija(e).isNotBlank())
        assertTrue(ListenRules.darija(9).contains("الميكروفون")) // insufficient permissions
    }

    @Test fun finalWinsOtherwiseLastPartial() {
        assertEquals("شنوة الطقس", ListenRules.best(" شنوة الطقس ", "شنوة"))
        assertEquals("شنوة", ListenRules.best(null, "شنوة"))
        assertEquals("", ListenRules.best("", null))
    }
}
