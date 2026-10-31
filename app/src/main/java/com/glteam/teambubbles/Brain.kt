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
    private val client = OkHttpClient.Builder()
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
        data class Failure(val error: BrainError) : Result()
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

        for (model in models.ifEmpty { Gemini.STATIC_MODELS }) {
            var thinking = true
            var search = useSearch
            var schema = true
            var http1 = false
            var tries = 0
            while (tries++ < 4) {
                if (clock() - start > CHAIN_MS) {
                    log("chain budget spent")
                    return Result.Failure(if (errors.all { it == BrainError.QUOTA } && errors.isNotEmpty()) BrainError.QUOTA else BrainError.TIMEOUT)
                }
                val url = "${Gemini.BASE}/models/$model:generateContent"
                val body = Gemini.requestBody(systemPrompt, history, userText, model, search, schema, thinking, imageJpegBase64 = imageJpegBase64)
                val response = try {
                    transport.post(url, key, body, http1)
                } catch (e: UnknownHostException) {
                    log("$model offline: ${e.message}")
                    return Result.Failure(BrainError.OFFLINE)
                } catch (e: InterruptedIOException) {
                    log("$model timeout (http1=$http1)")
                    if (!http1) { http1 = true; continue }
                    errors += BrainError.TIMEOUT
                    break
                } catch (e: IOException) {
                    log("$model io: ${e.message}")
                    errors += BrainError.OFFLINE
                    break
                }
                val code = response.code
                val text = response.body
                log("$model → HTTP $code")
                when {
                    code == 200 -> {
                        if (ReplyParser.isBlocked(text)) return Result.Failure(BrainError.BLOCKED)
                        val reply = ReplyParser.parse(ReplyParser.text(text))
                        if (reply.reply.isBlank()) { errors += BrainError.EMPTY; break }
                        return Result.Answer(reply, model)
                    }
                    code == 401 || text.contains("API_KEY_INVALID") || text.contains("API key not valid") ->
                        return Result.Failure(BrainError.BAD_KEY)
                    code == 400 && thinking && text.contains("thinking", ignoreCase = true) -> { thinking = false; continue }
                    code == 400 && search && (text.contains("search", true) || text.contains("tool", true)) -> { search = false; continue }
                    code == 400 && schema && (text.contains("schema", true) || text.contains("mime", true)) -> { schema = false; continue }
                    code == 429 -> { errors += BrainError.QUOTA; break }
                    code == 404 || code == 403 -> { errors += BrainError.NO_MODEL; break }
                    code >= 500 -> { errors += BrainError.SERVER; break }
                    else -> { errors += BrainError.SERVER; break }
                }
            }
        }
        return Result.Failure(summarise(errors))
    }

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
        val other = mutableListOf<String>()
        val pings = listed.map { model ->
            val t0 = clock()
            val code = try {
                transport.post(
                    "${Gemini.BASE}/models/$model:generateContent", key,
                    Gemini.requestBody("Reply with one word.", emptyList(), "hi", model, useSearch = false, useSchema = false, useThinking = false, maxOutputTokens = 5),
                    http1 = false,
                ).code
            } catch (e: IOException) { -1 }
            when (code) {
                200 -> working += model
                429 -> busy += model
                404, 403 -> Unit // not available for this key: dropped from the list
                else -> other += model
            }
            "$model: ${pingLabel(code)} (${clock() - t0} ms)"
        }
        val ordered = (working + busy + other).ifEmpty { listed }
        val summary = when {
            working.isNotEmpty() -> "المفتاح يخدم ✅ — Mouou يبدا بـ ${working.first()}"
            busy.isNotEmpty() -> "المفتاح صحيح ✅ أما الحصّة المجانية مشغولة توا (429). Mouou يجرّب الموديلات وحدو، ولا عاود الاختبار بعد دقيقة."
            else -> "المفتاح صحيح ✅ أما حتى موديل ما جاوب توا — شوف سجلّ الأخطاء"
        }
        return KeyTest(true, summary, ordered, pings)
    }

    private fun pingLabel(code: Int) = when (code) {
        200 -> "يخدم ✅"
        429 -> "مشغول (429)"
        404, 403 -> "موش متوفّر ($code)"
        -1 -> "ما وصلش"
        else -> "HTTP $code"
    }

    /** After a real answer, the model that answered goes first next time (fewer failed tries). */
    fun promote(models: List<String>, answered: String): List<String> =
        if (models.firstOrNull() == answered) models else listOf(answered) + models.filter { it != answered }

    data class KeyTest(val ok: Boolean, val summary: String, val models: List<String>, val pings: List<String>)

    companion object {
        const val PER_TRY_MS = 12_000L
        const val CHAIN_MS = 30_000L
    }
}
