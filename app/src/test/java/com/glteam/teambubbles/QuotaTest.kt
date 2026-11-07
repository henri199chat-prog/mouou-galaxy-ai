package com.glteam.teambubbles

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.6.1: Ayman's «كمّلنا الحصّة» on every model — read Google's real reason, and the 10 s timeout bug. */
class QuotaTest {
    private fun body429(quotaId: String, metric: String, value: String, retry: String?, message: String = "You exceeded your current quota.") =
        JSONObject().put("error", JSONObject().put("code", 429).put("status", "RESOURCE_EXHAUSTED").put("message", message)
            .put("details", JSONArray()
                .put(JSONObject().put("@type", "type.googleapis.com/google.rpc.QuotaFailure").put("violations", JSONArray().put(
                    JSONObject().put("quotaMetric", metric).put("quotaId", quotaId).put("quotaValue", value))))
                .apply { if (retry != null) put(JSONObject().put("@type", "type.googleapis.com/google.rpc.RetryInfo").put("retryDelay", retry)) })).toString()

    private val perDay = body429("GenerateRequestsPerDayPerProjectPerModel-FreeTier", "generativelanguage.googleapis.com/generate_content_free_tier_requests", "20", "31s")
    private val perMinute = body429("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "generativelanguage.googleapis.com/generate_content_free_tier_requests", "10", "41.2s")
    private val searchQuota = body429("SearchGroundingRequestsPerDayPerProject-FreeTier", "generativelanguage.googleapis.com/search_grounding_requests", "500", null)

    @Test fun detailsAreRead() {
        val d = QuotaInfo.parse(perDay)
        assertTrue(d.perDay); assertFalse(d.perMinute); assertFalse(d.search)
        assertEquals("20", d.limit); assertEquals(31, d.retrySec)
        val m = QuotaInfo.parse(perMinute)
        assertTrue(m.perMinute); assertEquals(42, m.retrySec)
        assertTrue(m.short().contains("دقيقة") && m.short().contains("42s"))
        assertTrue(QuotaInfo.parse(searchQuota).search)
    }

    @Test fun messageOnlyFormIsReadToo() {
        val msg = "You exceeded your current quota.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 0, model: gemini-3.8-flash\nPlease retry in 12.5s."
        val q = QuotaInfo.parse(JSONObject().put("error", JSONObject().put("code", 429).put("message", msg)).toString())
        assertEquals("0", q.limit); assertEquals(13, q.retrySec)
        assertTrue(QuotaInfo.hint(listOf(q), 0).contains("limit 0"))
        assertNull(QuotaInfo.parse("not json").limit)
    }

    @Test fun hintSaysHowLongToWait() {
        val now = java.time.ZonedDateTime.of(2026, 11, 7, 3, 20, 0, 0, java.time.ZoneId.of("Africa/Tunis")).toInstant().toEpochMilli()
        assertEquals("09:00", QuotaInfo.dailyResetTunis(now)) // midnight in California = 9 in the morning in Tunis (winter)
        assertTrue(QuotaInfo.hint(listOf(QuotaInfo.parse(perDay), QuotaInfo.parse(perDay)), now).contains("09:00"))
        assertEquals("استنى 42 ثانية وعاود.", QuotaInfo.hint(listOf(QuotaInfo.parse(perMinute), QuotaInfo.parse(perDay)), now))
        assertEquals("", QuotaInfo.hint(emptyList(), now))
    }

    private class Script(val responses: MutableList<GeminiTransport.Response>) : GeminiTransport {
        val bodies = mutableListOf<String>()
        val models = mutableListOf<String>()
        override fun post(url: String, apiKey: String, json: String, http1: Boolean): GeminiTransport.Response {
            bodies += json; models += url.substringAfter("/models/").substringBefore(":")
            return responses.removeAt(0)
        }
        override fun get(url: String, apiKey: String) = GeminiTransport.Response(200, "{}")
    }

    private fun ok(reply: String) = GeminiTransport.Response(200, JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "{\"reply\":\"$reply\"}")))))).toString())

    @Test fun searchQuotaSpentMeansSameModelWithoutSearch() {
        val t = Script(mutableListOf(GeminiTransport.Response(429, searchQuota), ok("بلا بحث")))
        val r = Brain(t).ask("AIza" + "x".repeat(35), listOf("m1", "m2"), "sys", emptyList(), "سؤال", useSearch = true) as Brain.Result.Answer
        assertEquals("بلا بحث", r.reply.reply)
        assertEquals(listOf("m1", "m1"), t.models) // same model, not the next one
        assertTrue(t.bodies[0].contains("google_search"))
        assertFalse(t.bodies[1].contains("google_search"))
    }

    @Test fun everyModelOutForTheDayGivesTheResetTime() {
        val t = Script(mutableListOf(GeminiTransport.Response(429, perDay), GeminiTransport.Response(429, perDay)))
        val f = Brain(t).ask("AIza" + "x".repeat(35), listOf("m1", "m2"), "sys", emptyList(), "سؤال", useSearch = false) as Brain.Result.Failure
        assertEquals(BrainError.QUOTA, f.error)
        assertTrue(f.hint, f.hint.contains("بتوقيت تونس"))
        assertTrue(f.detail, f.detail.contains("m1 429 يومي 20"))
    }

    @Test fun httpWaitsTheWholeTryNotOkHttpsDefault10s() {
        val c = OkHttpTransport().client
        assertEquals(Brain.PER_TRY_MS.toInt(), c.readTimeoutMillis)
        assertEquals(Brain.PER_TRY_MS.toInt(), c.callTimeoutMillis)
        assertTrue(c.connectTimeoutMillis >= 10_000)
    }

    @Test fun sighDeflatesThenComesBackExactly() {
        val b = MascotBounce()
        b.sigh(1000)
        assertTrue(b.squash(1000 + MascotBounce.SIGH_MS / 2) < 0.95f)
        assertTrue(b.offsetY(1000 + MascotBounce.SIGH_MS / 2) > 0f) // sinks (down = positive)
        assertTrue(b.active(1000 + 100))
        assertEquals(1f, b.squash(1000 + MascotBounce.SIGH_MS), 0f)
        assertEquals(0f, b.offsetY(1000 + MascotBounce.SIGH_MS), 0f)
        assertFalse(b.active(1000 + MascotBounce.SIGH_MS))
    }
}
