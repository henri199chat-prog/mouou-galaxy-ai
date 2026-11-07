package com.glteam.teambubbles

import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The network, behind an interface so the fallback rules are tested with a fake (spec §21: model 1 returns 429). */
interface GeminiTransport {
    data class Response(val code: Int, val body: String)

    /** POST JSON. Throws [InterruptedIOException] on timeout, [UnknownHostException] when offline. */
    fun post(url: String, apiKey: String, json: String, http1: Boolean): Response

    fun get(url: String, apiKey: String): Response
}

class OkHttpTransport : GeminiTransport {
    // 2.6.1: OkHttp's DEFAULT read timeout is 10 s, so "25 s per try" was really 10 s: a model still thinking was
    // cut off (Ayman's key test: «ما وصلش (10003 ms)»). Every timeout now follows the per-try budget.
    internal val client = OkHttpClient.Builder()
        .connectTimeout(15_000, TimeUnit.MILLISECONDS)
        .readTimeout(Brain.PER_TRY_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(Brain.PER_TRY_MS, TimeUnit.MILLISECONDS)
        .callTimeout(Brain.PER_TRY_MS, TimeUnit.MILLISECONDS)
        .build()
    // HTTP/2 can stall on some networks: one retry over HTTP/1.1 (spec §19 bug 8).
    private val http1Client = client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    override fun post(url: String, apiKey: String, json: String, http1: Boolean): GeminiTransport.Response {
        val request = Request.Builder().url(url).header("x-goog-api-key", apiKey).post(json.toRequestBody(jsonType)).build()
        (if (http1) http1Client else client).newCall(request).execute().use { r ->
            return GeminiTransport.Response(r.code, r.body?.string().orEmpty())
        }
    }

    override fun get(url: String, apiKey: String): GeminiTransport.Response {
        val request = Request.Builder().url(url).header("x-goog-api-key", apiKey).get().build()
        client.newCall(request).execute().use { r ->
            return GeminiTransport.Response(r.code, r.body?.string().orEmpty())
        }
    }
}

/**
 * One message → one answer, walking the model list on failure (spec §10.2):
 * 429 → next model (never stop); 404/403 → next; timeout → one HTTP/1.1 retry, then next; 5xx → next;
 * 400 about thinking → same model without thinkingConfig; 400 about search/schema → same model without
 * them; bad key (401 / API_KEY_INVALID) → stop. Per try 12 s, whole chain at most 30 s.
 */
class Brain(
    private val transport: GeminiTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    sealed class Result {
        data class Answer(val reply: BrainReply, val model: String) : Result()
        /** [detail] = what each model answered, e.g. "gemini-3.8-flash 429 · gemini-3.6-flash timeout" (for the card and the log). */
        data class Failure(val error: BrainError, val detail: String = "", val hint: String = "") : Result()
    }

    fun ask(
        apiKey: String?,
        models: List<String>,
        systemPrompt: String,
        history: List<Turn>,
        userText: String,
        useSearch: Boolean,
        imageJpegBase64: String? = null,
    ): Result {
        val key = apiKey?.let(KeySanitizer::clean).orEmpty()
        if (key.isEmpty()) return Result.Failure(BrainError.NO_KEY)
        val start = clock()
        val errors = mutableListOf<BrainError>()
        val trace = mutableListOf<String>()
        val quotas = mutableListOf<QuotaInfo>()
        fun fail(e: BrainError) = Result.Failure(e, trace.joinToString(" · "), if (e == BrainError.QUOTA) QuotaInfo.hint(quotas, clock()) else "")
            .also { log("failed: ${it.error} — ${it.detail}") }

        for (model in models.ifEmpty { Gemini.STATIC_MODELS }) {
            var thinking = true
            var search = useSearch
            var schema = true
            var http1 = false
            var tries = 0
            while (tries++ < 4) {
                if (clock() - start > CHAIN_MS) {
                    log("chain budget spent")
                    trace += "وقت كامل ${CHAIN_MS / 1000}s"
                    return fail(if (errors.all { it == BrainError.QUOTA } && errors.isNotEmpty()) BrainError.QUOTA else BrainError.TIMEOUT)
                }
                val url = "${Gemini.BASE}/models/$model:generateContent"
                val body = Gemini.requestBody(systemPrompt, history, userText, model, search, schema, thinking, imageJpegBase64 = imageJpegBase64)
                val response = try {
                    transport.post(url, key, body, http1)
                } catch (e: UnknownHostException) {
                    log("$model offline: ${e.message}")
                    trace += "$model offline"
                    return fail(BrainError.OFFLINE)
                } catch (e: InterruptedIOException) {
                    log("$model timeout (http1=$http1)")
                    if (!http1 && clock() - start < CHAIN_MS / 2) { http1 = true; continue }
                    trace += "$model timeout"
                    errors += BrainError.TIMEOUT
                    break
                } catch (e: IOException) {
                    log("$model io: ${e.message}")
                    trace += "$model io"
                    errors += BrainError.OFFLINE
                    break
                }
                val code = response.code
                val text = response.body
                log("$model → HTTP $code${if (code != 200) " " + text.take(160).replace('\n', ' ') else ""}")
                when {
                    code == 200 -> {
                        if (ReplyParser.isBlocked(text)) { trace += "$model blocked"; return fail(BrainError.BLOCKED) }
                        val reply = ReplyParser.parse(ReplyParser.text(text))
                        if (reply.reply.isBlank()) { trace += "$model empty"; errors += BrainError.EMPTY; break }
                        return Result.Answer(reply, model)
                    }
                    code == 401 || text.contains("API_KEY_INVALID") || text.contains("API key not valid") ||
                        text.contains("API key expired") -> {
                        // Google's own words (e.g. "API key expired") + which key the app really sent, safely
                        // (first 4 chars + length), so a screenshot tells a stale saved key from a refused new one.
                        trace += "$model $code: ${googleMessage(text)} — ${KeySanitizer.describe(key)}"
                        return fail(BrainError.BAD_KEY)
                    }
                    code == 400 && thinking && text.contains("thinking", ignoreCase = true) -> { thinking = false; continue }
                    code == 400 && search && (text.contains("search", true) || text.contains("tool", true)) -> { search = false; continue }
                    code == 400 && schema && (text.contains("schema", true) || text.contains("mime", true)) -> { schema = false; continue }
                    code == 429 -> {
                        val q = QuotaInfo.parse(text)
                        log("$model 429 quota: ${q.ids.joinToString().ifBlank { "?" }} limit=${q.limit ?: "?"} retry=${q.retrySec ?: "?"}s")
                        // Only the Google-search quota is spent: the same model answers without search (2.6.1).
                        if (q.search && search) { trace += "$model 429 بحث"; search = false; continue }
                        quotas += q
                        trace += "$model 429${q.short()}"
                        errors += BrainError.QUOTA
                        break
                    }
                    code == 404 || code == 403 -> { trace += "$model $code"; errors += BrainError.NO_MODEL; break }
                    else -> { trace += "$model $code"; errors += BrainError.SERVER; break }
                }
            }
        }
        return fail(summarise(errors))
    }

    /** The "message" of a Google error body, shortened; never contains the key. */
    private fun googleMessage(body: String): String = runCatching {
        org.json.JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("").ifBlank { body.take(80) }.take(90)

    /** Every model failed: say the most useful reason. */
    private fun summarise(errors: List<BrainError>): BrainError = when {
        errors.isEmpty() -> BrainError.SERVER
        errors.all { it == BrainError.QUOTA } -> BrainError.QUOTA
        errors.all { it == BrainError.NO_MODEL } -> BrainError.NO_MODEL
        BrainError.QUOTA in errors -> BrainError.QUOTA
        else -> errors.last()
    }

    /** Key test (spec §10.8): models.list + a 5-token ping on the first 2 models. */
    fun testKey(apiKey: String): KeyTest {
        val key = KeySanitizer.clean(apiKey)
        if (key.isEmpty()) return KeyTest(false, BrainError.NO_KEY.darija, emptyList(), emptyList())
        // No verdict on the shape: Google decides (a valid key was refused for "looking strange" in 2.3.0).
        val list = try {
            transport.get("${Gemini.BASE}/models?pageSize=200", key)
        } catch (e: IOException) {
            return KeyTest(false, "ما نجمتش نوصل: ${e.javaClass.simpleName}", emptyList(), emptyList())
        }
        if (list.code == 400 || list.code == 401 || list.code == 403) {
            return KeyTest(false, "Google قال إنو المفتاح موش صالح (HTTP ${list.code}) — ${KeySanitizer.describe(key)}", emptyList(), emptyList())
        }
        if (list.code != 200) return KeyTest(false, "models.list → HTTP ${list.code}", emptyList(), emptyList())
        val listed = Gemini.orderModels(list.body).ifEmpty { Gemini.STATIC_MODELS }
        // models.list answered: the key itself is valid. Now find which models really answer for THIS key
        // (free keys often get 404 on pro models and 429 on some flash models — Ayman's 2.3.2 test).
        val working = mutableListOf<String>()
        val busy = mutableListOf<String>()
        val lastQuotas = mutableListOf<QuotaInfo>()
        val other = mutableListOf<String>()
        val pings = mutableListOf<String>()
        for (model in listed) {
            if (working.isNotEmpty()) break // one working model is enough: don't spend the free quota on the rest
            val t0 = clock()
            val code = try {
                val r = transport.post(
                    "${Gemini.BASE}/models/$model:generateContent", key,
                    Gemini.requestBody("Reply with one word.", emptyList(), "hi", model, useSearch = false, useSchema = false, useThinking = false, maxOutputTokens = 5),
                    http1 = false,
                )
                if (r.code == 429) lastQuotas += QuotaInfo.parse(r.body)
                r.code
            } catch (e: IOException) { -1 }
            when (code) {
                200 -> working += model
                429 -> busy += model
                404, 403 -> Unit // not available for this key: dropped from the list
                else -> other += model
            }
            pings += "$model: ${pingLabel(code)} (${clock() - t0} ms)"
        }
        val untried = listed.filter { it !in working && it !in busy && it !in other && pings.none { p -> p.startsWith("$it:") } }
        val ordered = (working + untried + busy + other).ifEmpty { listed }
        val summary = when {
            working.isNotEmpty() -> "المفتاح يخدم ✅ — Mouou يبدا بـ ${working.first()}"
            busy.isNotEmpty() -> "المفتاح صحيح ✅ أما الحصّة المجانية مشغولة توا (429). Mouou يجرّب الموديلات وحدو، ولا عاود الاختبار بعد دقيقة."
            else -> "المفتاح صحيح ✅ أما حتى موديل ما جاوب توا — شوف سجلّ الأخطاء"
        }
        return KeyTest(true, summary, ordered, pings + listOfNotNull(QuotaInfo.hint(lastQuotas, clock()).takeIf { busy.isNotEmpty() && it.isNotBlank() }))
    }

    private fun pingLabel(code: Int) = when (code) {
        200 -> "يخدم ✅"
        429 -> "الحصّة كملت (429)"
        404, 403 -> "موش متوفّر ($code)"
        -1 -> "ما وصلش"
        else -> "HTTP $code"
    }

    /** After a real answer, the model that answered goes first next time (fewer failed tries). */
    fun promote(models: List<String>, answered: String): List<String> =
        if (models.firstOrNull() == answered) models else listOf(answered) + models.filter { it != answered }

    data class KeyTest(val ok: Boolean, val summary: String, val models: List<String>, val pings: List<String>)

    companion object {
        // Spec said 12 s / 30 s, but a thinking model with Google search can need more than 12 s for one answer
        // (2.3.3 on the phone: no answer at all). Low thinking + 25 s per try + 60 s for the whole chain.
        const val PER_TRY_MS = 25_000L
        const val CHAIN_MS = 60_000L
    }
}
