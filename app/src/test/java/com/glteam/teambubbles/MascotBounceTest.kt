package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MascotBounceTest {
    @Test fun neutralWhenNothingHappened() {
        val b = MascotBounce()
        assertEquals(1f, b.squash(1234), 0f)
        assertEquals(0f, b.offsetX(1234), 0f)
        assertEquals(0f, b.offsetY(1234), 0f)
        assertFalse(b.active(1234))
    }

    @Test fun landingSquashesThenReturnsExactlyToNeutral() {
        val b = MascotBounce()
        b.land(1000)
        assertTrue(b.squash(1000) < 0.9f)
        assertTrue((1000L..1449L).all { b.squash(it) in 0.85f..1.15f })
        assertEquals(1f, b.squash(1000 + MascotBounce.LAND_MS), 0f)
        assertFalse(b.active(1000 + MascotBounce.LAND_MS))
    }

    @Test fun hopGoesUpAndComesBack() {
        val b = MascotBounce()
        b.hop(0)
        val peak = (0L..1000L).minOf { b.offsetY(it) }
        assertTrue("peak $peak", peak < -0.3f)
        assertEquals(0f, b.offsetY(MascotBounce.HOP_TOTAL_MS), 0f)
        assertEquals(1f, b.squash(MascotBounce.HOP_TOTAL_MS), 0f)
    }

    @Test fun shakeIsAbout9HzAndDiesOut() {
        val b = MascotBounce()
        b.shake(0)
        var crossings = 0
        var prev = b.offsetX(1)
        for (t in 2L..333L) {
            val v = b.offsetX(t)
            if (prev < 0 != v < 0) crossings++
            prev = v
        }
        assertTrue("crossings $crossings", crossings in 5..7) // 9 Hz → 6 zero crossings in 1/3 s
        assertTrue(abs(b.offsetX(700)) < 0.01f)
        assertEquals(0f, b.offsetX(MascotBounce.SHAKE_MS), 0f)
    }
}
