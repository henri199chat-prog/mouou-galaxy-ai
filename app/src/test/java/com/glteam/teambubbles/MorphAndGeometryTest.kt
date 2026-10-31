package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphAndGeometryTest {

    private fun runSpring(spring: MorphSpring, frameMs: Float): Pair<Float, Float> {
        var elapsed = 0f
        var peak = spring.value
        while (spring.step(frameMs)) {
            elapsed += frameMs
            peak = maxOf(peak, spring.value)
            check(elapsed < 5000f) { "spring never settled" }
        }
        return elapsed to peak
    }

    @Test fun springDropsWithSmallOvershootAndSettlesFast() {
        val s = MorphSpring().apply { target = 1f }
        val (ms, peak) = runSpring(s, 8.33f)
        assertEquals(1f, s.value, 0f)
        assertTrue("overshoot $peak", peak in 1.01f..1.12f)
        assertTrue("settle $ms ms", ms in 250f..700f)
    }

    @Test fun sameMotionAt60And120Hz() {
        val a = MorphSpring().apply { target = 1f }
        val b = MorphSpring().apply { target = 1f }
        repeat(10) { a.step(16.666f) }
        repeat(20) { b.step(8.333f) }
        assertEquals(a.value, b.value, 0.02f)
    }

    @Test fun longStallDoesNotExplode() {
        val s = MorphSpring().apply { target = 1f }
        s.step(5000f)
        assertTrue(s.value in -0.5f..1.5f)
    }

    @Test fun snapStopsEverything() {
        val s = MorphSpring().apply { target = 1f }
        s.step(30f)
        s.snapTo(0f)
        assertFalse(s.step(16f))
        assertEquals(0f, s.value, 0f)
    }

    // S25 Ultra: 1440 × 3120 px, 3.5 px/dp, status bar ≈ 130 px.
    private val card = IslandGeometry.card(1440, 3120, 130, 3.5f)

    @Test fun cardIsAbout13PercentAndNeverOver15() {
        assertEquals(405, card.height)
        assertTrue(card.height <= 3120 * 0.15)
    }

    @Test fun cardStartsBelowTheStatusBar() {
        assertTrue(card.top > 130)
    }

    @Test fun cardIsCentredAndAtMost420dp() {
        assertEquals(1440 - 56, card.width)
        assertEquals(1440 / 2, card.centerX)
        val wide = IslandGeometry.card(2560, 1600, 80, 2f)
        assertEquals(840, wide.width)
    }

    @Test fun expandedWindowCoversPillAndCard() {
        val pill = Box(768, 25, 1034, 116)
        val w = IslandGeometry.expandedWindow(pill, card, 42, 1440)
        assertTrue(w.left <= card.left && w.right >= card.right)
        assertTrue(w.top <= pill.top && w.bottom >= card.bottom + 42)
        assertTrue(w.left >= 0 && w.right <= 1440)
    }
}
