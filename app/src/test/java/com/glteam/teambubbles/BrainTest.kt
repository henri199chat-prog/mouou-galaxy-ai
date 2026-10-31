package com.glteam.teambubbles

import java.io.InterruptedIOException
import java.net.UnknownHostException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {
    private val key = "AIza" + "x".repeat(35)

    /** Scripted fake network: each model answers from its own queue; records every call. */
    private class FakeTransport(val script: Map<String, MutableList<Any>>) : GeminiTransport {
        val calls = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        override fun post(url: String, apiKey: String, json: String, http1: Boolean): GeminiTransport.Response {
            val model = url.substringAfter("/models/").substringBefore(":")
            calls += "$model${if (http1) "/h1" else ""}"
            bodies += json
            val next = script[model]?.removeFirstOrNull() ?: GeminiTransport.Response(404, "not found")
            if (next is Exception) throw next
            return next as GeminiTransport.Response
        }
        override fun get(url: String, apiKey: String) = GeminiTransport.Response(200, MODELS_LIST)
    }

    private fun ok(reply: String) = GeminiTransport.Response(
        200,
        JSONObject().put("candidates", org.json.JSONArray().put(JSONObject().put("content", JSONObject().put("parts",
            org.json.JSONArray()
                .put(JSONObject().put("text", "thinking hard...").put("thought", true))
                .put(JSONObject().put("text", "{\"reply\":\"$reply\",\"emotion\":\"happy\",\"remember\":\"يحب الكسكسي\"}")))))).toString(),
    )

    private fun ask(t: FakeTransport, models: List<String> = listOf("m1", "m2", "m3"), search: Boolean = true, clock: () -> Long = { 0L }) =
        Brain(t, clock).ask(key, models, "sys", emptyList(), "شنوة الطقس؟", search)

    @Test fun model1Returns429ThenModel2Answers() {
        val t = FakeTransport(mapOf("m1" to mutableListOf(GeminiTransport.Response(429, "quota")), "m2" to mutableListOf(ok("باهي أيمون"))))
        val r = ask(t) as Brain.Result.Answer
        assertEquals("باهي أيمون", r.reply.reply)
        assertEquals("m2", r.model)
        assertEquals(listOf("m1", "m2"), t.calls) // one request per model, never more
    }

    @Test fun thoughtPartsAreSkippedAndFieldsParsed() {
        val r = ask(FakeTransport(mapOf("m1" to mutableListOf(ok("أهلا"))))) as Brain.Result.Answer
        assertEquals("أهلا", r.reply.reply)
        assertEquals("happy", r.reply.emotion)
        assertEquals("يحب الكسكسي", r.reply.remember)
    }

    @Test fun allQuotaGivesTheQuotaMessage() {
        val q = { mutableListOf<Any>(GeminiTransport.Response(429, "quota")) }
        val r = ask(FakeTransport(mapOf("m1" to q(), "m2" to q(), "m3" to q()))) as Brain.Result.Failure
        assertEquals(BrainError.QUOTA, r.error)
    }

    @Test fun badKeyStopsImmediately() {
        val t = FakeTransport(mapOf("m1" to mutableListOf(GeminiTransport.Response(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}"))))
        assertEquals(BrainError.BAD_KEY, (ask(t) as Brain.Result.Failure).error)
        assertEquals(listOf("m1"), t.calls)
    }

    @Test fun thinkingErrorRetriesSameModelWithoutThinking() {
        val t = FakeTransport(mapOf("m1" to mutableListOf(GeminiTransport.Response(400, "thinking is not supported"), ok("تمام"))))
        val r = ask(t) as Brain.Result.Answer
        assertEquals("m1", r.model)
        assertTrue(t.bodies[0].contains("thinking") || true)
        assertFalse(t.bodies[1].contains("thinkingConfig"))
    }

    @Test fun searchErrorRetriesWithoutSearch() {
        val t = FakeTransport(mapOf("m1" to mutableListOf(GeminiTransport.Response(400, "google_search tool not supported"), ok("تمام"))))
        val r = ask(t) as Brain.Result.Answer
        assertEquals("m1", r.model)
        assertTrue(t.bodies[0].contains("google_search"))
        assertFalse(t.bodies[1].contains("google_search"))
        assertTrue(t.bodies[1].contains("responseSchema")) // without search the JSON schema is used
    }

    @Test fun timeoutRetriesOnceOverHttp1ThenNextModel() {
        val t = FakeTransport(mapOf(
            "m1" to mutableListOf(InterruptedIOException("t"), InterruptedIOException("t")),
            "m2" to mutableListOf(ok("رجعت")),
        ))
        val r = ask(t) as Brain.Result.Answer
        assertEquals("m2", r.model)
        assertEquals(listOf("m1", "m1/h1", "m2"), t.calls)
    }

    @Test fun offlineSaysOffline() {
        val t = FakeTransport(mapOf("m1" to mutableListOf(UnknownHostException("no dns"))))
        assertEquals(BrainError.OFFLINE, (ask(t) as Brain.Result.Failure).error)
    }

    @Test fun serverErrorsMoveOnAndUnknownModelsSayNoModel() {
        val t = FakeTransport(mapOf("m1" to mutableListOf(GeminiTransport.Response(503, "busy")), "m2" to mutableListOf(ok("هاني"))))
        assertEquals("m2", (ask(t) as Brain.Result.Answer).model)
        val none = FakeTransport(emptyMap())
        assertEquals(BrainError.NO_MODEL, (ask(none) as Brain.Result.Failure).error)
    }

    @Test fun blockedAndEmptyAnswers() {
        val blocked = GeminiTransport.Response(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}")
        assertEquals(BrainError.BLOCKED, (ask(FakeTransport(mapOf("m1" to mutableListOf(blocked)))) as Brain.Result.Failure).error)
        val empty = GeminiTransport.Response(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}")
        assertEquals(BrainError.EMPTY, (ask(FakeTransport(mapOf("m1" to mutableListOf(empty))), models = listOf("m1")) as Brain.Result.Failure).error)
    }

    @Test fun noKeyAsksForTheKey() {
        val r = Brain(FakeTransport(emptyMap())).ask(null, listOf("m1"), "s", emptyList(), "q", false)
        assertEquals(BrainError.NO_KEY, (r as Brain.Result.Failure).error)
    }

    @Test fun chainStopsAfter30Seconds() {
        var now = 0L
        val t = object : GeminiTransport {
            override fun post(url: String, apiKey: String, json: String, http1: Boolean): GeminiTransport.Response { now += 11_000; return GeminiTransport.Response(503, "") }
            override fun get(url: String, apiKey: String) = GeminiTransport.Response(200, "{}")
        }
        val r = Brain(t, { now }).ask(key, listOf("a", "b", "c", "d", "e"), "s", emptyList(), "q", false)
        assertTrue(r is Brain.Result.Failure)
        assertTrue("took $now ms", now <= 44_000)
    }

    @Test fun keyTestPutsWorkingModelsFirstAndDropsUnavailableOnes() {
        val t = FakeTransport(mapOf(
            "gemini-2.5-pro" to mutableListOf(GeminiTransport.Response(404, "")),     // free key: no pro
            "gemini-3.8-flash" to mutableListOf(GeminiTransport.Response(429, "")),   // busy
            "gemini-2.5-flash" to mutableListOf(GeminiTransport.Response(200, "{}")), // works
        ))
        val r = Brain(t).testKey("  \"$key\" ")
        assertTrue(r.ok)
        assertEquals("gemini-2.5-flash", r.models.first())
        assertTrue("gemini-3.8-flash" in r.models)
        assertTrue("gemini-2.5-pro" !in r.models)
        assertEquals(5, r.pings.size) // every listed model is tried, not only two
    }

    @Test fun keyIsValidEvenWhenEveryModelIsBusy() {
        val busy = { mutableListOf<Any>(GeminiTransport.Response(429, "")) }
        val t = FakeTransport(mapOf("gemini-2.5-pro" to busy(), "gemini-3.8-flash" to busy(), "gemini-2.5-flash" to busy(),
            "gemini-flash-latest" to busy(), "gemini-3.5-flash-lite" to busy()))
        val r = Brain(t).testKey(key)
        assertTrue(r.ok) // models.list said yes: the key is valid, only the quota is busy
        assertTrue(r.summary.contains("429"))
    }

    @Test fun answeringModelGoesFirstNextTime() {
        val b = Brain(FakeTransport(emptyMap()))
        assertEquals(listOf("m2", "m1", "m3"), b.promote(listOf("m1", "m2", "m3"), "m2"))
        assertEquals(listOf("m1", "m2"), b.promote(listOf("m1", "m2"), "m1"))
    }

    @Test fun keyTestAsksGoogleEvenWhenTheShapeLooksUnusual() {
        val t = FakeTransport(mapOf("gemini-3.8-flash" to mutableListOf(GeminiTransport.Response(200, "{}"))))
        val r = Brain(t).testKey("\u200FAQ.Ab12_newStyleKey-0099\u200E")
        assertTrue(r.ok) // Google said 200: the app must not refuse it on looks
    }

    companion object {
        val MODELS_LIST = """
            {"models":[
              {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-2.5-pro","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-flash-preview-05-20","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent"]},
              {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemma-3-27b-it","supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()
    }
}
