package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureEngineTest {
    private val log = mutableListOf<String>()
    private var flingUsed = true
    private val cb = object : GestureEngine.Callbacks {
        override fun onTap() { log += "tap" }
        override fun onLongPress() { log += "long" }
        override fun onLongPressEnd(heldMs: Long) { log += "longEnd" }
        override fun onDragStart() { log += "dragStart" }
        override fun onDrag(dx: Float, dy: Float, vx: Float, vy: Float) { if (log.lastOrNull() != "drag") log += "drag" }
        override fun onDragEnd(vx: Float, vy: Float) { log += "dragEnd" }
        override fun onFlingUp(): Boolean { log += "fling"; return flingUsed }
        override fun onTwoFingerDrag(dx: Float, dy: Float) { if (log.lastOrNull() != "two") log += "two" }
        override fun onTwoFingerEnd() { log += "twoEnd" }
    }
    // 3.5 px/dp like the S25 Ultra; slop = 8 dp × 3.
    private val g = GestureEngine(slopPx = 84f, longPressMs = 480, density = 3.5f, callbacks = cb)

    @Test fun shortStillPressIsATap() {
        g.down(0, 500f, 60f)
        g.move(50, 520f, 70f) // jitter within slop
        g.up(120, 515f, 66f)
        assertEquals(listOf("tap"), log)
    }

    @Test fun longPressFiresWhileStillDown() {
        g.down(0, 500f, 60f)
        g.tick(300)
        assertTrue(log.isEmpty())
        g.tick(480)
        assertEquals(listOf("long"), log)
        g.up(1500, 500f, 60f)
        assertEquals(listOf("long", "longEnd"), log)
    }

    @Test fun movingPastSlopIsADragNotATap() {
        g.down(0, 500f, 400f)
        g.move(40, 560f, 420f)
        g.move(80, 700f, 450f)
        g.up(120, 760f, 460f)
        assertEquals(listOf("dragStart", "drag", "dragEnd"), log)
    }

    @Test fun dragNeverTurnsIntoLongPress() {
        g.down(0, 500f, 400f)
        g.move(40, 700f, 400f)
        g.tick(900)
        g.up(1000, 700f, 400f)
        assertEquals(listOf("dragStart", "drag", "dragEnd"), log)
    }

    @Test fun fastUpwardSwipeIsAFling() {
        g.down(0, 500f, 900f)
        g.move(30, 500f, 800f)
        g.move(60, 500f, 650f)
        g.up(80, 500f, 560f) // −340 px (≈ 97 dp) in 80 ms ≈ 4250 px/s ≫ 3500 px/s
        assertEquals(listOf("dragStart", "drag", "fling"), log)
    }

    @Test fun unusedFlingStillEndsTheDrag() {
        flingUsed = false
        g.down(0, 500f, 900f)
        g.move(30, 500f, 800f)
        g.up(80, 500f, 560f)
        assertEquals(listOf("dragStart", "drag", "fling", "dragEnd"), log)
    }

    @Test fun slowUpwardDragIsNotAFling() {
        g.down(0, 500f, 900f)
        g.move(200, 500f, 800f)
        g.move(400, 500f, 700f)
        g.up(600, 500f, 690f)
        assertEquals(listOf("dragStart", "drag", "dragEnd"), log)
    }

    @Test fun secondFingerMakesATwoFingerMoveAndNeverATap() {
        g.down(0, 500f, 60f)
        g.pointerDown(600f, 80f)
        g.move(30, 650f, 120f)
        g.pointerUp()
        g.up(200, 650f, 120f)
        g.tick(900)
        assertEquals(listOf("two", "twoEnd"), log)
    }
}
