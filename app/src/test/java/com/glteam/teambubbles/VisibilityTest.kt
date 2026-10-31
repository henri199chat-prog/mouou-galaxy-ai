package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibilityTest {
    @Test fun hideGoesSmoothlyToZeroWithoutOvershoot() {
        val v = VisibilityAnim()
        v.hide(1000)
        var prev = 1f
        for (t in 1000L..1000L + VisibilityAnim.DURATION_MS) {
            val x = v.value(t)
            assertTrue(x in 0f..1f)
            assertTrue("must only go down", x <= prev + 1e-6f)
            prev = x
        }
        assertEquals(0f, v.value(5000), 0f)
        assertFalse(v.animating(5000))
    }

    @Test fun reversingMidWayNeverJumps() {
        val v = VisibilityAnim()
        v.hide(0)
        val mid = v.value(190)
        v.show(190)
        assertEquals(mid, v.value(190), 1e-6f)
        assertEquals(1f, v.value(190 + VisibilityAnim.DURATION_MS), 0f)
    }

    @Test fun eyesOpenOnlyAtTheEndOfShowing() {
        assertEquals(0f, VisibilityAnim.eyeOpenness(0.5f), 0f)
        assertEquals(1f, VisibilityAnim.eyeOpenness(1f), 0f)
        assertTrue(VisibilityAnim.eyeOpenness(0.8f) in 0.1f..0.9f)
    }

    @Test fun hidesOnlyWhenNobodyIsUsingIt() {
        assertTrue(HideAfter.mayHide(5_000, cardOpen = false, typing = false, fingerDown = false))
        assertFalse(HideAfter.mayHide(0, cardOpen = false, typing = false, fingerDown = false)) // never
        assertFalse(HideAfter.mayHide(5_000, cardOpen = true, typing = false, fingerDown = false))
        assertFalse(HideAfter.mayHide(5_000, cardOpen = false, typing = true, fingerDown = false))
        assertFalse(HideAfter.mayHide(5_000, cardOpen = false, typing = false, fingerDown = true))
    }

    @Test fun everyChoiceHasAnArabicLabel() {
        for (ms in HideAfter.CHOICES_MS) assertTrue(HideAfter.label(ms).isNotBlank())
        assertEquals("أبدًا", HideAfter.label(0))
    }

    @Test fun emergeStartsHiddenThenGrowsOut() {
        val v = VisibilityAnim()
        v.snapHidden(1000)
        assertEquals(0f, v.value(1000), 0f)
        v.show(1000)
        assertTrue(v.value(1100) in 0.01f..0.99f)
        assertEquals(1f, v.value(1000 + VisibilityAnim.DURATION_MS), 0f)
    }

    @Test fun cameraTapZoneCoversTheHoleAndTheStatusBar() {
        val hole = Box(700, 40, 740, 80)
        val z = cameraTapZone(hole, statusBarBottom = 130, margin = 49, screenWidth = 1440)
        assertTrue(z.left <= hole.left && z.right >= hole.right)
        assertEquals(0, z.top)
        assertEquals(130, z.bottom)
        assertTrue(z.width < 200) // small: the rest of the status bar stays free
    }
}
