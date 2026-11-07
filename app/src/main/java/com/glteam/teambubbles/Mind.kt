package com.glteam.teambubbles

/** Claude's side of the brain: one message → a [BrainReply] (same JSON/tags protocol as Gemini) or a reason. */
class ClaudeBrain(private val transport: ClaudeTransport, private val log: (String) -> Unit = {}) {
    sealed class Result {
        data class Answer(val reply: BrainReply) : Result()
        data class Failure(val kind: Claude.Fail?, val detail: String, val refused: Boolean = false) : Result()
    }

    data class KeyTest(val ok: Boolean, val summary: String)

    fun ask(rawKey: String?, req: Claude.Request): Result {
        val key = rawKey?.let(ClaudeKey::clean).orEmpty()
        if (key.isEmpty()) return Result.Failure(Claude.Fail.BAD_KEY, "no key")
        val a = try {
            transport.send(key, req)
        } catch (e: Claude.Error) {
            log("claude ${e.kind}: ${e.detail}")
            return Result.Failure(e.kind, e.detail)
        }
        log("claude → ${a.stop}${if (a.searches > 0) " · ${a.searches} search" else ""}")
        if (a.refused) return Result.Failure(null, "refusal", refused = true)
        val reply = ReplyParser.parse(a.text)
        if (reply.reply.isBlank()) return Result.Failure(Claude.Fail.SERVER, "empty (${a.stop})")
        return Result.Answer(reply)
    }

    /** Settings «اختبار»: free model check first, then a tiny paid ping (credit). Never shows the key. */
    fun testKey(raw: String): KeyTest {
        val key = ClaudeKey.clean(raw)
        if (key.isEmpty()) return KeyTest(false, "حطّ مفتاح Claude الأول (من platform.claude.com ← API Keys)")
        if (ClaudeKey.isGeminiKey(key)) return KeyTest(false, "هذا مفتاح Gemini موش Claude! مفتاح Claude يبدا بـ sk-ant-")
        val name = try { transport.check(key) } catch (e: Claude.Error) { return KeyTest(false, line(e, key)) }
        try { transport.ping(key) } catch (e: Claude.Error) { return KeyTest(e.kind != Claude.Fail.BAD_KEY && e.kind != Claude.Fail.NO_CREDIT && e.kind != Claude.Fail.OFFLINE, line(e, key)) }
        return KeyTest(true, "المفتاح يخدم ✅ — Mouou يحكي توا بـ $name، وGemini احتياط")
    }

    private fun line(e: Claude.Error, key: String) = when (e.kind) {
        Claude.Fail.BAD_KEY -> "Anthropic قال إنو المفتاح موش صالح (${e.detail.take(40)}) — ${ClaudeKey.describe(key)}"
        Claude.Fail.NO_CREDIT -> "المفتاح صحيح ✅ أما الرصيد فارغ: زيد رصيد من platform.claude.com ← Billing. Gemini يجاوب في الأثناء."
        Claude.Fail.NO_MODEL -> "المفتاح صحيح أما ${Claude.LABEL} موش متوفّر للحساب هذا (${e.detail.take(40)})"
        Claude.Fail.RATE_LIMIT, Claude.Fail.OVERLOADED -> "المفتاح صحيح ✅ أما Claude مشغول توا، عاود الاختبار بعد دقيقة"
        Claude.Fail.OFFLINE -> "ما نجمتش نوصل لـ Anthropic: ثبّت الإنترنت"
        Claude.Fail.TIMEOUT -> "Anthropic طوّل وما جاوبش، عاود"
        else -> "صار مشكل: ${e.detail.take(80)}"
    }
}

/**
 * Mouou's brain router (Ayman, 2026-11-07): Claude Sonnet first; when Claude cannot answer (busy, no credit, bad
 * key, server trouble), the same question goes to Gemini so Mouou never stays silent. Offline or a refusal stops
 * there (Gemini would not do better). [Outcome.note] tells Ayman, under the answer, when Claude needs his attention.
 */
class Mind(private val claude: ClaudeBrain, private val gemini: Brain, private val log: (String) -> Unit = {}) {
    data class Outcome(val result: Brain.Result, val byClaude: Boolean, val note: String = "")

    fun ask(
        claudeKey: String?,
        geminiKey: String?,
        geminiModels: List<String>,
        persona: String,
        live: String,
        history: List<Turn>,
        userText: String,
        useSearch: Boolean,
        imageJpegBase64: String?,
    ): Outcome {
        val hasClaude = !claudeKey.isNullOrBlank()
        val hasGemini = !geminiKey.isNullOrBlank()
        var trace = ""
        var note = ""
        if (hasClaude) {
            when (val r = claude.ask(claudeKey, Claude.Request(persona, live, history, userText, imageJpegBase64, useSearch))) {
                is ClaudeBrain.Result.Answer -> return Outcome(Brain.Result.Answer(r.reply, Claude.MODEL), byClaude = true)
                is ClaudeBrain.Result.Failure -> {
                    trace = "Claude ${r.detail.take(60)}"
                    if (r.refused) return Outcome(Brain.Result.Failure(BrainError.CLAUDE_REFUSED, trace), byClaude = true)
                    if (r.kind == Claude.Fail.OFFLINE) return Outcome(Brain.Result.Failure(BrainError.OFFLINE, trace), byClaude = true)
                    note = when (r.kind) {
                        Claude.Fail.NO_CREDIT -> "رصيد Claude كمل، جاوبك Gemini"
                        Claude.Fail.BAD_KEY -> "مفتاح Claude موش صالح، جاوبك Gemini"
                        Claude.Fail.NO_MODEL -> "${Claude.LABEL} موش متوفّر، جاوبك Gemini"
                        else -> ""
                    }
                    if (!hasGemini) return Outcome(Brain.Result.Failure(errorFor(r.kind), trace), byClaude = true)
                    log("→ Gemini (fallback)")
                }
            }
        }
        val g = gemini.ask(geminiKey, geminiModels, SystemPrompt.join(persona, live), history, userText, useSearch, imageJpegBase64)
        return when (g) {
            is Brain.Result.Answer -> Outcome(g, byClaude = false, note = note)
            is Brain.Result.Failure -> Outcome(
                Brain.Result.Failure(if (!hasClaude && !hasGemini) BrainError.NO_KEY else g.error, listOf(trace, g.detail).filter { it.isNotBlank() }.joinToString(" · ")),
                byClaude = false,
                note = note,
            )
        }
    }

    private fun errorFor(kind: Claude.Fail?) = when (kind) {
        Claude.Fail.BAD_KEY -> BrainError.CLAUDE_BAD_KEY
        Claude.Fail.NO_CREDIT -> BrainError.CLAUDE_NO_CREDIT
        Claude.Fail.RATE_LIMIT, Claude.Fail.OVERLOADED -> BrainError.CLAUDE_BUSY
        Claude.Fail.TIMEOUT -> BrainError.TIMEOUT
        Claude.Fail.OFFLINE -> BrainError.OFFLINE
        else -> BrainError.SERVER
    }

    companion object {
        /** After an answer, only a GEMINI model moves to the front of the Gemini list (Claude must never enter it). */
        fun promoteGemini(models: List<String>, answered: String): List<String> =
            if (answered !in models || models.firstOrNull() == answered) models else listOf(answered) + models.filter { it != answered }
    }
}
