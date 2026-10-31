package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementTest {
    // S25 Ultra: 1440 × 3120 px, 3.5 px/dp, status bar 130 px, navigation bar from 3000 px.
    private val d = 3.5f
    private val sb = 130
    private val nav = 3000

    private fun card(pill: Box) = IslandGeometry.cardFor(pill, 1440, 3120, sb, nav, d)

    @Test fun dockedPillOpensBelowTheStatusBar() {
        val c = card(Box(760, 25, 1025, 116))
        assertTrue(c.top >= sb + 14)
        assertEquals(405, c.height)
    }

    @Test fun pillInTheMiddleOpensBelowIt() {
        val pill = Box(500, 1200, 765, 1291)
        val c = card(pill)
        assertTrue(c.top >= pill.bottom)
    }

    @Test fun lowPillOpensAboveIt() {
        val pill = Box(500, 2850, 765, 2941)
        val c = card(pill)
        assertTrue("card ${c.bottom} must end above pill ${pill.top}", c.bottom <= pill.top)
        assertTrue(c.bottom <= nav)
    }

    @Test fun cardStaysOnScreenForAPillAtTheEdge() {
        val c = card(Box(1170, 800, 1435, 891))
        assertTrue(c.left >= 28 && c.right <= 1440 - 28)
    }

    @Test fun clampKeepsPillOnScreenAndAboveNavBar() {
        val off = Box(-200, 3100, 65, 3191)
        val c = IslandGeometry.clampPill(off, 1440, nav, 28)
        assertTrue(c.left >= 0 && c.bottom <= nav - 28)
        assertEquals(off.width, c.width)
    }

    @Test fun magnetWithin70dpAnd110dp() {
        val dock = Box(587, 134, 852, 225)
        assertTrue(IslandGeometry.shouldDock(dock.offset(300, 200), dock, d)) // 86 dp / 57 dp
        assertFalse(IslandGeometry.shouldDock(dock.offset(0, 300), dock, d))  // 86 dp down
        assertFalse(IslandGeometry.shouldDock(dock.offset(420, 0), dock, d))  // 120 dp sideways
    }

    @Test fun dockBelowStatusBarIsUnderTheCamera() {
        val pill = IslandGeometry.dockBelowStatusBar(1440, Box(700, 40, 740, 80), 265, 91, sb, 7)
        assertEquals(720, pill.centerX, )
        assertEquals(sb + 7, pill.top)
    }
}
