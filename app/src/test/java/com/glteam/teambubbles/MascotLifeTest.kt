package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MascotLifeTest {
    @Test fun breathingStaysWithin2Point2Percent() {
        val life = MascotLife(Random(1))
        for (t in 0L..6400L step 16) {
            val s = life.breath(t)
            assertTrue("scale $s at $t", s in 0.977f..1.023f)
        }
        assertEquals(1f, life.breath(0), 1e-6f)
    }

    @Test fun blinksEvery3700To5000msFor130ms() {
        val life = MascotLife(Random(7))
        val closedStarts = mutableListOf<Long>()
        var wasOpen = true
        for (t in 0L..30_000L) {
            val open = life.eyeOpenness(t) > 0.5f
            if (wasOpen && !open) closedStarts += t
            wasOpen = open
        }
        assertTrue("blinks: ${closedStarts.size}", closedStarts.size in 5..14)
        // Either a normal 3.7–5 s gap, or the quick second blink of a double blink.
        closedStarts.zipWithNext { a, b -> assertTrue("gap ${b - a}", b - a in 3600..5100 || b - a in 150..300) }
        // Never three blinks in a row: after a double blink comes a normal gap.
        closedStarts.zipWithNext { a, b -> b - a }.zipWithNext { g1, g2 -> assertTrue(!(g1 < 400 && g2 < 400)) }
    }

    @Test fun eyesFullyCloseMidBlink() {
        val life = MascotLife(Random(3))
        val min = (0L..10_000L).minOf { life.eyeOpenness(it) }
        assertTrue("min openness $min", min < 0.05f)
    }

    @Test fun glancesStaySmall() {
        val life = MascotLife(Random(5))
        for (t in 0L..20_000L step 8) {
            val l = life.look(t)
            assertTrue(kotlin.math.abs(l.x) <= 0.06f && kotlin.math.abs(l.y) <= 0.05f)
        }
    }
}
