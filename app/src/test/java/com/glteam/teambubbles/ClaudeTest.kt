package com.glteam.teambubbles

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The REAL Anthropic SDK path (SdkClaudeTransport) against a local fake server: what Mouou sends to Anthropic
 * and how each answer or error is read. No network, no key.
 */
class ClaudeSdkTest {
    private lateinit var server: MockWebServer
    private val key = "sk-ant-api03-" + "k".repeat(40)

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun transport(timeoutMs: Long = 5_000) = SdkClaudeTransport(server.url("/").toString().trimEnd('/'), timeoutMs)

    private fun message(stop: String, vararg blocks: JSONObject) = JSONObject()
        .put("id", "msg_1").put("type", "message").put("role", "assistant").put("model", Claude.MODEL)
        .put("content", JSONArray(blocks.toList())).put("stop_reason", stop).put("stop_sequence", JSONObject.NULL)
        .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5)).toString()

    private fun text(t: String) = JSONObject().put("type", "text").put("text", t)

    private fun reply(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)

    private val req = Claude.Request(
        persona = "أنت Mouou",
        live = "[سياق توا] الوقت 10:00",
        history = listOf(
            Turn(false, "{\"reply\":\"orphan\"}", 0), // starts with the assistant: dropped
            Turn(true, "أهلا", 1), Turn(false, "{\"reply\":\"أهلا أيمون\"}", 2),
        ),
        userText = "شنوة على الشاشة؟",
        imageJpegBase64 = "AAAA",
        useSearch = true,
    )

    @Test fun requestCarriesModelEffortFallbackCacheImageAndSearch() {
        server.enqueue(reply(message("end_turn", text("{\"reply\":\"نشوف تصويرة\",\"emotion\":\"happy\"}"))))
        val a = transport().send(key, req)
        assertEquals("{\"reply\":\"نشوف تصويرة\",\"emotion\":\"happy\"}", a.text)
        assertFalse(a.refused)

        val r = server.takeRequest()
        assertTrue(r.path!!.startsWith("/v1/messages"))
        assertEquals(key, r.getHeader("x-api-key"))
        assertNull(r.getHeader("authorization")) // the key is sent once, in the documented header
        assertTrue(r.getHeader("anthropic-beta")!!.contains(Claude.FALLBACK_BETA))
        val body = JSONObject(r.body.readUtf8())
        assertEquals(Claude.MODEL, body.getString("model"))
        assertEquals("default", body.getString("fallbacks"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertFalse(body.has("temperature")) // refused by Sonnet 5.5
        // persona cached, live context after the breakpoint
        val system = body.getJSONArray("system")
        assertEquals("أنت Mouou", system.getJSONObject(0).getString("text"))
        assertEquals("ephemeral", system.getJSONObject(0).getJSONObject("cache_control").getString("type"))
        assertFalse(system.getJSONObject(1).has("cache_control"))
        // history alternates from a user turn; the new question carries the screenshot first, then the text
        val msgs = body.getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "user"), (0 until msgs.length()).map { msgs.getJSONObject(it).getString("role") })
        val last = msgs.getJSONObject(2).getJSONArray("content")
        assertEquals("image", last.getJSONObject(0).getString("type"))
        assertEquals("image/jpeg", last.getJSONObject(0).getJSONObject("source").getString("media_type"))
        assertEquals("AAAA", last.getJSONObject(0).getJSONObject("source").getString("data"))
        assertEquals("شنوة على الشاشة؟", last.getJSONObject(1).getString("text"))
        // web search, at most 2 per message, from Tunis
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_search_20260209", tool.getString("type"))
        assertEquals(2, tool.getInt("max_uses"))
        assertEquals("TN", tool.getJSONObject("user_location").getString("country"))
    }

    @Test fun noSearchNoImageMeansNoToolAndTextOnly() {
        server.enqueue(reply(message("end_turn", text("{\"reply\":\"ok\"}"))))
        transport().send(key, req.copy(useSearch = false, imageJpegBase64 = null))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("tools"))
        val last = body.getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }
        val content = last.get("content")
        val parts = if (content is JSONArray) content else JSONArray().put(JSONObject().put("type", "text").put("text", content))
        assertEquals(1, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
    }

    @Test fun pausedSearchTurnIsContinuedOnce() {
        val search = JSONObject().put("type", "server_tool_use").put("id", "srvtoolu_1").put("name", "web_search")
            .put("input", JSONObject().put("query", "طقس تونس"))
        server.enqueue(reply(message("pause_turn", search)))
        server.enqueue(reply(message("end_turn", text("{\"reply\":\"الطقس باهي\"}"))))
        val a = transport().send(key, req)
        assertEquals("{\"reply\":\"الطقس باهي\"}", a.text)
        assertEquals(1, a.searches)
        server.takeRequest()
        val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals("assistant", second.getJSONObject(second.length() - 1).getString("role")) // the paused turn sent back
    }

    @Test fun fallbackBetaRefusedIsRetriedWithoutIt() {
        server.enqueue(reply(JSONObject().put("type", "error").put("error", JSONObject().put("type", "invalid_request_error")
            .put("message", "fallbacks: not available for this account")).toString(), 400))
        server.enqueue(reply(message("end_turn", text("{\"reply\":\"مرحبا\"}"))))
        val t = transport()
        assertEquals("{\"reply\":\"مرحبا\"}", t.send(key, req).text)
        assertTrue(JSONObject(server.takeRequest().body.readUtf8()).has("fallbacks"))
        val second = server.takeRequest()
        assertFalse(JSONObject(second.body.readUtf8()).has("fallbacks"))
        assertFalse(second.getHeader("anthropic-beta").orEmpty().contains(Claude.FALLBACK_BETA))
        // and the next message does not try it again
        server.enqueue(reply(message("end_turn", text("{\"reply\":\"ok\"}"))))
        t.send(key, req)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("fallbacks"))
    }

    @Test fun otherBadRequestsAreNotRetried() {
        assertEquals(Claude.Fail.BAD_REQUEST, failKind(400, "invalid_request_error", "messages: roles must alternate"))
        assertEquals(1, server.requestCount)
    }

    @Test fun refusalIsReported() {
        server.enqueue(reply(message("refusal")))
        assertTrue(transport().send(key, req).refused)
    }

    private fun failKind(code: Int, type: String, msg: String): Claude.Fail {
        server.enqueue(reply(JSONObject().put("type", "error").put("error", JSONObject().put("type", type).put("message", msg)).toString(), code))
        return try { transport().send(key, req); error("no error") } catch (e: Claude.Error) { e.kind }
    }

    @Test fun errorsAreClassifiedWithoutRetries() {
        assertEquals(Claude.Fail.BAD_KEY, failKind(401, "authentication_error", "invalid x-api-key"))
        assertEquals(Claude.Fail.NO_CREDIT, failKind(400, "invalid_request_error", "Your credit balance is too low to access the Anthropic API."))
        assertEquals(Claude.Fail.RATE_LIMIT, failKind(429, "rate_limit_error", "slow down"))
        assertEquals(Claude.Fail.OVERLOADED, failKind(529, "overloaded_error", "Overloaded"))
        assertEquals(Claude.Fail.SERVER, failKind(500, "api_error", "boom"))
        assertEquals(5, server.requestCount) // maxRetries(0): Mouou's own fallback is Gemini, not the same call again
    }

    @Test fun slowServerIsATimeout() {
        server.enqueue(reply(message("end_turn", text("late"))).setBodyDelay(3, TimeUnit.SECONDS))
        val e = try { transport(timeoutMs = 800).send(key, req); null } catch (e: Claude.Error) { e }
        assertEquals(Claude.Fail.TIMEOUT, e?.kind)
    }

    @Test fun keyCheckReadsTheModelName() {
        server.enqueue(reply(JSONObject().put("type", "model").put("id", Claude.MODEL).put("display_name", "Claude Sonnet 5.5")
            .put("created_at", "2026-10-01T00:00:00Z").toString()))
        assertEquals("Claude Sonnet 5.5", transport().check(key))
        assertTrue(server.takeRequest().path!!.startsWith("/v1/models/${Claude.MODEL}"))
    }
}

/** The router and the Claude brain rules with fake networks. */
class MindTest {
    private class FakeClaude(vararg val script: Any) : ClaudeTransport {
        var calls = 0
        override fun send(key: String, req: Claude.Request): Claude.Answer {
            val next = script[calls++]
            if (next is Claude.Error) throw next
            return next as Claude.Answer
        }
        override fun check(key: String) = "Claude Sonnet 5.5"
        override fun ping(key: String) = Unit
    }

    private class FakeGemini(val answer: String?) : GeminiTransport {
        var calls = 0
        override fun post(url: String, apiKey: String, json: String, http1: Boolean): GeminiTransport.Response {
            calls++
            if (answer == null) return GeminiTransport.Response(429, "quota")
            val body = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", "{\"reply\":\"$answer\"}")))))).toString()
            return GeminiTransport.Response(200, body)
        }
        override fun get(url: String, apiKey: String) = GeminiTransport.Response(200, "{}")
    }

    private val claudeKey = "sk-ant-api03-abc"
    private val geminiKey = "AIza" + "x".repeat(35)

    private fun mind(c: FakeClaude, g: FakeGemini) = Mind(ClaudeBrain(c), Brain(g))
    private fun ask(m: Mind, ck: String? = claudeKey, gk: String? = geminiKey) =
        m.ask(ck, gk, listOf("gemini-3.6-flash"), "persona", "live", emptyList(), "سؤال", false, null)

    @Test fun claudeAnswersAndGeminiIsNotAsked() {
        val c = FakeClaude(Claude.Answer(false, "{\"reply\":\"أهلا من Claude\",\"emotion\":\"happy\"}"))
        val g = FakeGemini("من Gemini")
        val o = ask(mind(c, g))
        val a = o.result as Brain.Result.Answer
        assertEquals("أهلا من Claude", a.reply.reply)
        assertEquals("happy", a.reply.emotion)
        assertEquals(Claude.MODEL, a.model)
        assertTrue(o.byClaude)
        assertEquals(0, g.calls)
    }

    @Test fun busyClaudeFallsBackToGeminiSilently() {
        val g = FakeGemini("من Gemini")
        val o = ask(mind(FakeClaude(Claude.Error(Claude.Fail.RATE_LIMIT, "HTTP 429")), g))
        assertEquals("من Gemini", (o.result as Brain.Result.Answer).reply.reply)
        assertFalse(o.byClaude)
        assertEquals("", o.note) // a passing rush is not Ayman's problem
        assertEquals(1, g.calls)
    }

    @Test fun noCreditFallsBackAndTellsAyman() {
        val o = ask(mind(FakeClaude(Claude.Error(Claude.Fail.NO_CREDIT, "HTTP 400 credit balance")), FakeGemini("من Gemini")))
        assertEquals("من Gemini", (o.result as Brain.Result.Answer).reply.reply)
        assertTrue(o.note.contains("رصيد"))
    }

    @Test fun offlineAndRefusalDoNotTryGemini() {
        val g = FakeGemini("من Gemini")
        val off = ask(mind(FakeClaude(Claude.Error(Claude.Fail.OFFLINE, "offline")), g))
        assertEquals(BrainError.OFFLINE, (off.result as Brain.Result.Failure).error)
        val no = ask(mind(FakeClaude(Claude.Answer(true, "")), g))
        assertEquals(BrainError.CLAUDE_REFUSED, (no.result as Brain.Result.Failure).error)
        assertEquals(0, g.calls)
    }

    @Test fun withoutAGeminiKeyClaudesReasonIsShown() {
        val o = ask(mind(FakeClaude(Claude.Error(Claude.Fail.NO_CREDIT, "x")), FakeGemini(null)), gk = null)
        assertEquals(BrainError.CLAUDE_NO_CREDIT, (o.result as Brain.Result.Failure).error)
        val bad = ask(mind(FakeClaude(Claude.Error(Claude.Fail.BAD_KEY, "x")), FakeGemini(null)), gk = null)
        assertEquals(BrainError.CLAUDE_BAD_KEY, (bad.result as Brain.Result.Failure).error)
    }

    @Test fun withoutAClaudeKeyGeminiWorksAsBefore() {
        val c = FakeClaude()
        val o = ask(mind(c, FakeGemini("من Gemini")), ck = null)
        assertEquals("من Gemini", (o.result as Brain.Result.Answer).reply.reply)
        assertEquals(0, c.calls)
    }

    @Test fun noKeysAtAll() {
        val o = ask(mind(FakeClaude(), FakeGemini(null)), ck = null, gk = null)
        assertEquals(BrainError.NO_KEY, (o.result as Brain.Result.Failure).error)
    }

    @Test fun bothFailingSaysWhatEachAnswered() {
        val o = ask(mind(FakeClaude(Claude.Error(Claude.Fail.OVERLOADED, "HTTP 529")), FakeGemini(null)))
        val f = o.result as Brain.Result.Failure
        assertEquals(BrainError.QUOTA, f.error)
        assertTrue(f.detail, f.detail.startsWith("Claude HTTP 529") && f.detail.contains("429"))
    }

    @Test fun claudeNeverEntersTheGeminiModelList() {
        val list = listOf("gemini-3.8-flash", "gemini-3.6-flash")
        assertEquals(list, Mind.promoteGemini(list, Claude.MODEL))
        assertEquals(listOf("gemini-3.6-flash", "gemini-3.8-flash"), Mind.promoteGemini(list, "gemini-3.6-flash"))
    }

    @Test fun historyAlternatesFromAUserTurn() {
        val h = listOf(Turn(false, "a0", 0), Turn(true, "u1", 1), Turn(true, "u2", 2), Turn(false, "a1", 3), Turn(true, "u3", 4))
        assertEquals(listOf(true to "u1\nu2", false to "a1"), Claude.alternate(h))
        assertTrue(Claude.alternate(listOf(Turn(false, "only", 0))).isEmpty())
    }

    @Test fun keysAreCleanedAndAGeminiKeyIsSpotted() {
        assertEquals("sk-ant-api03-abc_D-9", ClaudeKey.clean("ANTHROPIC_API_KEY=‏sk-ant-api03-abc_D-9 ‬"))
        assertTrue(ClaudeKey.isGeminiKey(geminiKey))
        assertFalse(ClaudeKey.isGeminiKey(claudeKey))
        assertFalse(ClaudeKey.describe(claudeKey + "secretpart").contains("secretpart"))
        val t = ClaudeBrain(FakeClaude()).testKey(geminiKey)
        assertFalse(t.ok)
        assertTrue(t.summary.contains("Gemini"))
        assertTrue(ClaudeBrain(FakeClaude()).testKey(claudeKey).ok)
        assertNull(null)
    }

    @Test fun errorClassification() {
        assertEquals(Claude.Fail.NO_CREDIT, Claude.classify(400, "Your credit balance is too low"))
        assertEquals(Claude.Fail.BAD_KEY, Claude.classify(401, ""))
        assertEquals(Claude.Fail.NO_MODEL, Claude.classify(404, ""))
        assertEquals(Claude.Fail.OVERLOADED, Claude.classify(529, ""))
        assertEquals(Claude.Fail.BAD_REQUEST, Claude.classify(400, "messages: roles must alternate"))
    }
}

/** 2.6.0 liveliness: the glow lights up when an answer arrives, then settles. */
class GlowBloomTest {
    @Test fun risesFastThenFadesAndStops() {
        assertEquals(0f, GlowBloom.amount(0), 0f)
        assertEquals(1f, GlowBloom.amount(GlowBloom.RISE_MS.toLong()), 1e-3f)
        var prev = 1f
        for (t in GlowBloom.RISE_MS.toLong()..GlowBloom.TOTAL_MS - 1 step 20) {
            val a = GlowBloom.amount(t)
            assertTrue(a <= prev + 1e-6f)
            prev = a
        }
        assertTrue(GlowBloom.amount(GlowBloom.TOTAL_MS - 1) < 0.02f) // no visible jump when it stops
        assertEquals(0f, GlowBloom.amount(GlowBloom.TOTAL_MS), 0f)
        assertEquals(0f, GlowBloom.amount(-5), 0f)
        assertEquals(0f, GlowBloom.amount(1_000_000), 0f) // never bloomed (bloomAt = 0, uptime is large)
    }
}
