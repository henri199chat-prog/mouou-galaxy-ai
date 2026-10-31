package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSizeTest {
    @Test fun s25UltraCardIsAbout13Percent() {
        val h = CardSize.heightPx(3120)
        assertEquals(405, h)
        assertTrue(h <= 3120 * 0.15)
    }
}
