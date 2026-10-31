package com.glteam.teambubbles

import org.json.JSONArray
import org.json.JSONObject

/** Gemini API facts and pure helpers (spec §10). No Android types: everything here is unit-tested. */
object Gemini {
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** Spec §10.2 static order (verified by the previous builder, Oct 2026). Re-verified with models.list by the key test. */
    val STATIC_MODELS = listOf(
        "gemini-3.8-flash", "gemini-3.6-flash", "gemini-flash-latest",
        "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash",
    )

    private val SKIP = Regex("tts|live|image|audio|embed|exp|gemma|native|robotics|computer", RegexOption.IGNORE_CASE)
    private val DATED = Regex("-\\d{2}-\\d{2}$|-\\d{3,}$|-\\d{2}-\\d{4}$")
    private val VERSION = Regex("gemini-(\\d+(?:\\.\\d+)?)")

    /**
     * Builds the model list from a models.list response (spec §10.2): gemini flash/pro with
     * generateContent, skipping tts/live/image/audio/embed/exp/gemma and dated snapshots; ordered
     * pro → flash → latest → preview → lite, newest first; at most 7.
     */
    fun orderModels(modelsListJson: String): List<String> {
        val models = JSONObject(modelsListJson).optJSONArray("models") ?: return emptyList()
        val names = mutableListOf<String>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            val name = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
            val canGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!canGenerate || !name.startsWith("gemini") || !(name.contains("flash") || name.contains("pro"))) continue
            if (SKIP.containsMatchIn(name) || DATED.containsMatchIn(name)) continue
            names += name
        }
        return names.distinct()
            .sortedWith(compareBy<String> { rank(it) }.thenByDescending { version(it) }.thenBy { it })
            .take(7)
    }

    private fun rank(name: String) = when {
        name.contains("lite") -> 4
        name.contains("preview") -> 3
        name.contains("latest") -> 2
        name.contains("pro") -> 0
        else -> 1 // flash
    }

    private fun version(name: String) = VERSION.find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /** Thinking per model family (spec §10.2): Gemini 3 flash "medium", lite "low", 2.5 budget 1024. */
    fun thinkingConfig(model: String): JSONObject? = when {
        model.startsWith("gemini-2.5") -> JSONObject().put("thinkingBudget", if (model.contains("lite")) 512 else 1024)
        model.contains("lite") -> JSONObject().put("thinkingLevel", "low")
        model.startsWith("gemini-3") || model.contains("latest") -> JSONObject().put("thinkingLevel", "medium")
        else -> null
    }

    /** Request body for one message. Search and the JSON schema can be dropped when a model refuses them. */
    fun requestBody(
        systemPrompt: String,
        history: List<Turn>,
        userText: String,
        model: String,
        useSearch: Boolean,
        useSchema: Boolean,
        useThinking: Boolean,
        maxOutputTokens: Int = 4096,
    ): String {
        val contents = JSONArray()
        for (t in history) {
            contents.put(JSONObject().put("role", if (t.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))))

        val gen = JSONObject().put("temperature", 0.8).put("maxOutputTokens", maxOutputTokens)
        if (useThinking) thinkingConfig(model)?.let { gen.put("thinkingConfig", it) }
        // With search the API refuses a response schema, so JSON is asked for in the prompt instead (spec §10.2).
        if (useSchema && !useSearch) {
            gen.put("responseMimeType", "application/json")
            gen.put("responseSchema", replySchema())
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", contents)
            .put("generationConfig", gen)
        if (useSearch) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return body.toString()
    }

    private fun replySchema(): JSONObject {
        val props = JSONObject()
        for (k in listOf("reply", "mood", "emotion", "remember", "topic")) props.put(k, JSONObject().put("type", "STRING"))
        return JSONObject().put("type", "OBJECT").put("properties", props).put("required", JSONArray().put("reply"))
    }
}

/** One turn of the conversation; model turns are stored as the JSON the model returned (spec §10.4). */
data class Turn(val fromUser: Boolean, val text: String, val atMs: Long)

/** A parsed answer (spec §10.6 tags protocol). */
data class BrainReply(
    val reply: String,
    val emotion: String = "",
    val mood: String = "",
    val topic: String = "",
    val remember: String = "",
    val animal: String = "",
    val rawJson: String = "",
)

object ReplyParser {
    private val FENCE = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("\\[(mood|topic|animal|emotion):\\s*([a-zA-Z_]+)\\]")

    /** True when the response was blocked by safety filters. */
    fun isBlocked(responseJson: String): Boolean {
        val o = runCatching { JSONObject(responseJson) }.getOrNull() ?: return false
        if (o.optJSONObject("promptFeedback")?.has("blockReason") == true) return true
        val reason = o.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
        return reason == "SAFETY" || reason == "PROHIBITED_CONTENT" || reason == "BLOCKLIST"
    }

    /** The visible text of a generateContent response, skipping thought parts (spec §10.2 "Parse"). */
    fun text(responseJson: String): String {
        val parts = runCatching {
            JSONObject(responseJson).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
        }.getOrNull() ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        return sb.toString().trim()
    }

    /** Tolerant parse: JSON (plain, fenced or embedded) → fields; otherwise the text itself is the reply. */
    fun parse(text: String): BrainReply {
        val json = extractJson(text)
        var reply: String
        var emotion = ""
        var mood = ""
        var topic = ""
        var remember = ""
        if (json != null) {
            reply = json.optString("reply")
            emotion = json.optString("emotion")
            mood = json.optString("mood")
            topic = json.optString("topic")
            remember = json.optString("remember")
        } else {
            reply = text
        }
        // Tags inside the text win for animal; they are never shown or spoken.
        var animal = ""
        for (m in TAG.findAll(reply)) {
            val (key, value) = m.destructured
            when (key) {
                "animal" -> animal = value.lowercase()
                "mood" -> if (mood.isEmpty()) mood = value.lowercase()
                "emotion" -> if (emotion.isEmpty()) emotion = value.lowercase()
                "topic" -> if (topic.isEmpty()) topic = value.lowercase()
            }
        }
        reply = TAG.replace(reply, "").replace(Regex("\\s{2,}"), " ").trim()
        return BrainReply(reply, emotion.lowercase(), mood.lowercase(), topic.lowercase(), remember.trim(), animal, json?.toString() ?: "")
    }

    private fun extractJson(text: String): JSONObject? {
        val t = text.trim()
        runCatching { return JSONObject(t) }
        FENCE.find(t)?.let { m -> runCatching { return JSONObject(m.groupValues[1]) } }
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start in 0 until end) runCatching { return JSONObject(t.substring(start, end + 1)) }
        return null
    }
}

/**
 * Cleans a pasted key (spec §10.8). An Arabic phone's clipboard can wrap copied text in invisible
 * bidi marks (U+200E/U+200F, U+202A–U+202E, U+2066–U+2069) that made a correct key look "strange"
 * (Ayman, 2.3.0). A Google API key only uses letters, digits, '_', '-' (and maybe '.'), so everything else goes.
 */
object KeySanitizer {
    private val VALID = Regex("^AIza[0-9A-Za-z_\\-]{35}$")
    private val PREFIX = Regex("^\\s*GEMINI_API_KEY\\s*[=:]\\s*", RegexOption.IGNORE_CASE)
    private val NOT_KEY_CHAR = Regex("[^0-9A-Za-z_.\\-]") // "." kept in case of newer key formats

    fun clean(raw: String): String = raw.replace(PREFIX, "").replace(NOT_KEY_CHAR, "")

    /** The usual shape; only a hint, the key test asks Google, which is the only real judge. */
    fun looksValid(key: String) = VALID.matches(key)

    /** Safe description for the screen: the first 4 characters (always "AIza" for real keys) and the length. */
    fun describe(key: String) = "يبدا بـ «${key.take(4)}…» وطولو ${key.length} حرف"
}

/** Why a message got no answer, with the Darija line Mouou says (spec §10.3). */
enum class BrainError(val darija: String) {
    NO_KEY("باش نفهمك ونجاوبك لازمني مفتاح Gemini (مجاني) في الإعدادات أيمون"),
    BAD_KEY("مفتاح Gemini موش صالح ولا تسكّر، بدّلو في الإعدادات أيمون"),
    OFFLINE("ما نجمتش نوصل للسيرفر، ثبّت الإنترنت أيمون"),
    TIMEOUT("السيرفر طوّل برشا وما جاوبش، عاود أيمون"),
    QUOTA("كمّلنا الحصّة المجانية متاع Gemini توا، استنى دقيقة وعاود"),
    SERVER("سيرفر Gemini فيه مشكل توا، عاود بعد شوية"),
    NO_MODEL("موديل Gemini موش متوفّر للمفتاح هذا، شوف سجلّ الأخطاء"),
    EMPTY("Gemini رجّع جواب فارغ، عاود سؤالك أيمون"),
    BLOCKED("Gemini رفض يجاوب على هذا، جرّب بطريقة أخرى"),
}
