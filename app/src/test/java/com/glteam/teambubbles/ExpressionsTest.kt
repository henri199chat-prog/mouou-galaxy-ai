package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionsTest {
    @Test fun brainEmotionsAndAliasesMapToFaces() {
        assertEquals(Expression.LOVE, Expressions.fromEmotion("heart_eyes"))
        assertEquals(Expression.LAUGH, Expressions.fromEmotion("Laughing"))
        assertEquals(Expression.SLEEPY, Expressions.fromEmotion("tired"))
        assertEquals(Expression.SHY, Expressions.fromEmotion("blush"))
        assertEquals(Expression.IDLE, Expressions.fromEmotion("something-new"))
        assertEquals(Expression.IDLE, Expressions.fromEmotion(null))
    }

    @Test fun darijaWordsGiveAnInstantFace() {
        assertEquals(Expression.CRYING, Expressions.fromWords("اليوم نبكي برشا"))
        assertEquals(Expression.LOVE, Expressions.fromWords("نونو تسلّم عليك"))
        assertEquals(Expression.LAUGH, Expressions.fromWords("ههههه"))
        assertEquals(Expression.SHY, Expressions.fromWords("يعيشك موو"))
        assertNull(Expressions.fromWords("قداش الساعة"))
    }

    @Test fun faceSwitchesShapeAtTheMidpointBehindAQuickBlink() {
        val f = FaceAnim()
        f.set(Expression.HAPPY, 1000)
        assertEquals(1f, f.update(1000), 0.01f)
        assertEquals(Expression.IDLE, f.current)
        val mid = f.update(1000 + FaceAnim.DURATION_MS / 2)
        assertTrue("eyes nearly closed at the switch: $mid", mid < 0.05f)
        assertEquals(Expression.HAPPY, f.current)
        assertEquals(1f, f.update(1000 + FaceAnim.DURATION_MS + 1), 0f)
    }

    @Test fun heldFaceReturnsToIdleAfterTheHoldTime() {
        val f = FaceAnim()
        f.set(Expression.SAD, 0, holdMs = 2000)
        f.update(500)
        assertEquals(Expression.SAD, f.current)
        f.update(2000)                                // the hold ends: transition back starts
        f.update(2000 + FaceAnim.DURATION_MS + 1)
        assertEquals(Expression.IDLE, f.current)
    }
}
