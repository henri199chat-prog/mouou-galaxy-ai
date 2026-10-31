package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutPlacerTest {
    // S25 Ultra at 3.5 px/dp: centred punch-hole about 40 px wide near the top.
    private val screenW = 1440
    private val hole = Box(700, 40, 740, 80)
    private val pillW = 371 // 106 dp
    private val pillH = 119 // 34 dp
    private val gap = 28 // 8 dp
    private val margin = 42 // 12 dp

    private fun pill(w: Box) = Box(w.left + margin, w.top + margin, w.right - margin, w.bottom - margin)

    @Test fun rightSideSitsBesideTheHoleNotOverIt() {
        val p = pill(CutoutPlacer.idleWindow(screenW, hole, pillW, pillH, gap, margin, PillSide.RIGHT, 0))
        assertEquals(hole.right + gap, p.left)
        assertEquals(hole.centerY, p.centerY, 1)
        assertTrue(p.left >= hole.right)
    }

    @Test fun leftSideMirrorsWithAbsoluteCoordinates() {
        val p = pill(CutoutPlacer.idleWindow(screenW, hole, pillW, pillH, gap, margin, PillSide.LEFT, 0))
        assertEquals(hole.left - gap, p.right)
        assertTrue(p.right <= hole.left)
    }

    @Test fun windowIsOnlyPillPlusTouchMargin() {
        val w = CutoutPlacer.idleWindow(screenW, hole, pillW, pillH, gap, margin, PillSide.RIGHT, 0)
        assertEquals(pillW + 2 * margin, w.width)
        assertEquals(pillH + 2 * margin, w.height)
    }

    @Test fun noCutoutCentresAtTheTop() {
        val p = pill(CutoutPlacer.idleWindow(screenW, null, pillW, pillH, gap, margin, PillSide.RIGHT, 30))
        assertEquals(screenW / 2, p.centerX, 1)
        assertEquals(30, p.top)
    }

    @Test fun cornerCutoutKeepsPillOnScreen() {
        val cornerHole = Box(1300, 40, 1380, 100)
        val p = pill(CutoutPlacer.idleWindow(screenW, cornerHole, pillW, pillH, gap, margin, PillSide.RIGHT, 0))
        assertTrue(p.right <= screenW)
        assertTrue(p.left >= 0)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
