package com.glteam.teambubbles

/** Mouou's faces for phase 1 (spec §11, eyes only: never a mouth). The full gallery comes with the design pack. */
enum class Expression { IDLE, HAPPY, LAUGH, EXCITED, LOVE, SURPRISED, SAD, CRYING, WORRIED, ANGRY, CONFUSED, WINK, SLEEPY, SHY }

object Expressions {
    /** Brain emotion ids and aliases (spec §10.6) → face. Unknown ids keep the idle face. */
    fun fromEmotion(emotion: String?): Expression = when (emotion?.trim()?.lowercase()) {
        "happy", "content" -> Expression.HAPPY
        "laugh", "laughing" -> Expression.LAUGH
        "excited", "sparkle" -> Expression.EXCITED
        "love", "heart_eyes", "hearts" -> Expression.LOVE
        "surprised", "wow", "shocked" -> Expression.SURPRISED
        "sad", "gloomy" -> Expression.SAD
        "crying", "teary" -> Expression.CRYING
        "worried", "pleading" -> Expression.WORRIED
        "angry", "furious" -> Expression.ANGRY
        "confused", "dizzy", "curious", "think", "thinking" -> Expression.CONFUSED
        "wink" -> Expression.WINK
        "sleepy", "sleeping", "tired" -> Expression.SLEEPY
        "shy", "blush" -> Expression.SHY
        else -> Expression.IDLE
    }

    /** Instant reaction to Ayman's own words before the brain answers (spec §10.7 step 1). */
    private val KEYWORDS: List<Pair<Regex, Expression>> = listOf(
        Regex("نبكي|بكيت|😭") to Expression.CRYING,
        Regex("خايف|خفت|😨") to Expression.WORRIED,
        Regex("حزين|متقلق|ضايق|😢") to Expression.SAD,
        Regex("معصب|متغشش|😡") to Expression.ANGRY,
        Regex("نحبك|نونو|❤|😍") to Expression.LOVE,
        Regex("ههه|هاها|😂|🤣") to Expression.LAUGH,
        Regex("واو|معقول|wow", RegexOption.IGNORE_CASE) to Expression.SURPRISED,
        Regex("نعسان|تعبان|😴") to Expression.SLEEPY,
        Regex("يعيشك|شكرا|merci", RegexOption.IGNORE_CASE) to Expression.SHY,
        Regex("ما فهمتش|فهمتش|\\?\\?") to Expression.CONFUSED,
        Regex("مبروك|نجحت|🎉") to Expression.EXCITED,
    )

    fun fromWords(text: String): Expression? = KEYWORDS.firstOrNull { it.first.containsMatchIn(text) }?.second

    /** Faces happy enough for a hop. */
    fun isHappy(e: Expression) = e == Expression.HAPPY || e == Expression.LAUGH || e == Expression.EXCITED ||
        e == Expression.LOVE || e == Expression.WINK
}

/**
 * Face changes (spec §11: 320 ms smoothstep, shape switch at the midpoint): the eyes close into a quick
 * blink and reopen with the new shape. A held face eases back to idle after [holdMs].
 */
class FaceAnim {
    var current = Expression.IDLE
        private set
    private var next = Expression.IDLE
    private var changeAt = Long.MIN_VALUE
    private var holdUntil = Long.MAX_VALUE

    fun set(e: Expression, nowMs: Long, holdMs: Long = HOLD_MS) {
        if (e == next && nowMs < changeAt + DURATION_MS) { holdUntil = nowMs + holdMs; return }
        next = e
        changeAt = nowMs
        holdUntil = if (e == Expression.IDLE) Long.MAX_VALUE else nowMs + holdMs
    }

    /** Advances time; returns the eye-openness factor of the transition (1 = open, 0 = mid-switch). */
    fun update(nowMs: Long): Float {
        if (nowMs >= holdUntil && next != Expression.IDLE) {
            holdUntil = Long.MAX_VALUE
            next = Expression.IDLE
            changeAt = nowMs
        }
        val t = (nowMs - changeAt).toFloat() / DURATION_MS
        if (t !in 0f..1f) {
            current = next
            return 1f
        }
        if (t >= 0.5f) current = next // shape switch at the midpoint
        val d = kotlin.math.abs(t - 0.5f) * 2f // 1 → 0 → 1
        return d * d * (3 - 2 * d)
    }

    fun animating(nowMs: Long) = nowMs - changeAt in 0..DURATION_MS

    companion object {
        const val DURATION_MS = 320L
        const val HOLD_MS = 6_000L // stays while answering, then eases to idle (no voice yet in phase 1)
    }
}
