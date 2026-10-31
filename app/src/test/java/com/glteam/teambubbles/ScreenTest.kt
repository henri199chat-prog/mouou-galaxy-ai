package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTest {
    private class N(override val text: CharSequence?, override val description: CharSequence? = null, vararg val kids: N) : TextNode {
        override val childCount get() = kids.size
        override fun child(index: Int) = kids[index]
    }

    @Test fun readsTextDepthFirstAndSkipsDuplicates() {
        val tree = N(null, null, N("مرحبا"), N("مرحبا"), N(null, "زر الإرسال"), N("  كيفاش   حالك  "))
        assertEquals("مرحبا\nزر الإرسال\nكيفاش حالك", ScreenText.collect(listOf(tree)))
    }

    @Test fun respectsTheNodeAndCharacterLimits() {
        val many = N(null, null, *Array(1000) { N("line $it") })
        assertEquals(299, ScreenText.collect(listOf(many)).lines().size) // root + 299 children = 300 nodes
        val long = N(null, null, *Array(100) { N("x".repeat(100) + it) })
        assertTrue(ScreenText.collect(listOf(long)).length <= 4000)
    }

    @Test fun respectsTheDepthLimit() {
        var deep = N("bottom")
        repeat(60) { deep = N(null, null, deep) }
        assertEquals("", ScreenText.collect(listOf(deep)))
    }

    @Test fun screenshotIsScaledToAt1024() {
        assertEquals(473 to 1024, ScreenImage.fit(1440, 3120))
        assertEquals(800 to 600, ScreenImage.fit(800, 600))
    }

    @Test fun promptPrefixFollowsTheSpec() {
        val t = SystemPrompt.userTurnWithScreen("شنوة هذا؟", "Instagram\nReels", hasImage = true)
        assertTrue(t.startsWith("[النصوص اللي ظاهرة على الشاشة توا]\nInstagram\nReels\n\n[آخر سؤال أيمون]: شنوة هذا؟"))
        assertTrue(t.endsWith("[تصويرة الشاشة اللي نشوف فيها توا مرفوقة]"))
        val nothing = SystemPrompt.userTurnWithScreen("q", "", hasImage = false)
        assertTrue(nothing.endsWith(SystemPrompt.SCREEN_OFF_TAG))
    }

    @Test fun screenQuestionsAreRecognised() {
        assertTrue(SystemPrompt.isAboutScreen("تشوف الشاشة؟"))
        assertTrue(SystemPrompt.isAboutScreen("What do you see on my screen"))
        assertFalse(SystemPrompt.isAboutScreen("شنوة أحوالك"))
    }

    @Test fun imageGoesOnlyInTheLastUserTurn() {
        val body = Gemini.requestBody("s", listOf(Turn(true, "قبل", 0)), "توا", "gemini-2.5-flash", false, true, true, imageJpegBase64 = "QUJD")
        assertTrue(body.contains("\"inlineData\":{\"mimeType\":\"image/jpeg\",\"data\":\"QUJD\"}") || body.contains("\"data\":\"QUJD\""))
        assertEquals(1, Regex("inlineData").findAll(body).count())
    }
}
