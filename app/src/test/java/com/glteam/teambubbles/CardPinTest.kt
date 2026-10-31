package com.glteam.teambubbles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPinTest {
    @Test fun emptyCardClosesOnOutsideTapEvenWithKeyboardUp() {
        assertTrue(CardPin.outsideTapCollapses(""))
        assertTrue(CardPin.outsideTapCollapses("   "))
    }

    @Test fun draftIsNeverLostToAnOutsideTap() {
        assertFalse(CardPin.outsideTapCollapses("شنوة"))
    }

    @Test fun idleTimerWaitsWhileTypingOrDrafting() {
        assertTrue(CardPin.idleMayCollapse(typing = false, draft = ""))
        assertFalse(CardPin.idleMayCollapse(typing = true, draft = ""))
        assertFalse(CardPin.idleMayCollapse(typing = false, draft = "مرحبا"))
    }
}
