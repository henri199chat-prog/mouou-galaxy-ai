package com.glteam.teambubbles

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.messages.BetaBase64ImageSource
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaImageBlockParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaUserLocation
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.time.Duration

/**
 * Claude (Anthropic) — Mouou's main brain since 2.6.0 (Ayman chose Claude Sonnet, 2026-11-07); Gemini stays the
 * automatic fallback. Pure helpers here are unit-tested; [SdkClaudeTransport] is the official Anthropic SDK.
 */
object Claude {
    const val MODEL = "claude-sonnet-5-5"
    const val LABEL = "Claude Sonnet 5.5"
    /** One answer (with Mouou's low effort it takes a few seconds; web search adds some). Gemini gets the rest. */
    const val PER_TRY_MS = 25_000L
    /** Server-side fallback when Claude's safety classifiers decline: the API re-routes by itself ("default"). */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /** Everything one message needs, built from the same pieces as the Gemini request. */
    data class Request(
        /** Mouou's persona (§10.5): the same every message, so it is cached. */
        val persona: String,
        /** Time, facts, open app: changes every message, so it sits after the cache breakpoint. */
        val live: String,
        val history: List<Turn>,
        val userText: String,
        val imageJpegBase64: String?,
        val useSearch: Boolean,
        val maxTokens: Long = 4096,
    )

    /** The parts of a response Mouou uses, as plain data (so the rules are tested without the network). */
    data class Answer(val refused: Boolean, val text: String, val searches: Int = 0, val stop: String = "")

    enum class Fail { BAD_KEY, NO_CREDIT, NO_MODEL, RATE_LIMIT, OVERLOADED, SERVER, TIMEOUT, OFFLINE, BAD_REQUEST }

    class Error(val kind: Fail, val detail: String) : Exception(detail)

    /**
     * History as the API wants it: starts with a user turn and alternates (same-role neighbours merged);
     * a trailing user turn (a question that never got an answer) is dropped, the new question follows.
     */
    fun alternate(history: List<Turn>): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        for (t in history) {
            if (t.text.isBlank()) continue
            if (out.isEmpty() && !t.fromUser) continue
            if (out.isNotEmpty() && out.last().first == t.fromUser) {
                out[out.lastIndex] = t.fromUser to (out.last().second + "\n" + t.text)
            } else {
                out += t.fromUser to t.text
            }
        }
        if (out.isNotEmpty() && out.last().first) out.removeAt(out.lastIndex)
        return out
    }

    /** HTTP status + Anthropic's message → what Mouou does about it. */
    fun classify(status: Int, message: String): Fail = when {
        message.contains("credit balance", ignoreCase = true) || status == 402 -> Fail.NO_CREDIT
        status == 401 || status == 403 -> Fail.BAD_KEY
        status == 404 -> Fail.NO_MODEL
        status == 429 -> Fail.RATE_LIMIT
        status == 529 || status == 503 -> Fail.OVERLOADED
        status == 408 || status == 504 -> Fail.TIMEOUT
        status >= 500 -> Fail.SERVER
        else -> Fail.BAD_REQUEST
    }
}

/** A Claude key pasted on an Arabic phone: same invisible-mark cleaning as the Gemini key (1.5c), plus its env prefix. */
object ClaudeKey {
    private val PREFIX = Regex("^\\s*ANTHROPIC_API_KEY\\s*[=:]\\s*", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String = KeySanitizer.clean(raw.replace(PREFIX, ""))

    /** A Gemini key pasted in the Claude field (they look nothing alike, but both are "a key" for Ayman). */
    fun isGeminiKey(key: String) = key.startsWith("AIza")

    /** Safe description: the first 7 characters ("sk-ant-" for real keys) and the length, never the key. */
    fun describe(key: String) = "يبدا بـ «${key.take(7)}…» وطولو ${key.length} حرف"
}

/** The network behind an interface, so the brain rules are tested with a fake. Both calls throw [Claude.Error]. */
interface ClaudeTransport {
    /** One message (plus one continuation if the web search paused the turn). */
    fun send(key: String, req: Claude.Request): Claude.Answer

    /** Free check: the key works and Mouou's model is there. Returns the model's display name. */
    fun check(key: String): String

    /** A tiny real message (a fraction of a cent): proves the account has credit. */
    fun ping(key: String)
}

/** The official Anthropic SDK (anthropic-java). No retries inside: Mouou's own fallback is Gemini. */
class SdkClaudeTransport(
    private val baseUrl: String? = null,
    private val timeoutMs: Long = Claude.PER_TRY_MS,
) : ClaudeTransport {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    @Synchronized
    private fun client(key: String): AnthropicClient {
        client?.let { if (clientKey == key) return it }
        client?.close()
        // The documented API-key header (x-api-key). anthropic-java 3.6 would otherwise send the key as
        // "Authorization: Bearer"; with x-api-key present it adds nothing else (checked in ClaudeSdkTest).
        val b = AnthropicOkHttpClient.builder().apiKey(key).putHeader("X-Api-Key", key)
            .maxRetries(0).timeout(Duration.ofMillis(timeoutMs))
        baseUrl?.let { b.baseUrl(it) }
        return b.build().also { client = it; clientKey = key }
    }

    /** Once refused (400 about the fallback beta), it is left out for the rest of the app's life. */
    @Volatile private var fallbackRefused = false

    fun params(req: Claude.Request, withFallback: Boolean = !fallbackRefused): MessageCreateParams {
        val b = MessageCreateParams.builder()
            .model(Claude.MODEL)
            .maxTokens(req.maxTokens)
        if (withFallback) b.addBeta(Claude.FALLBACK_BETA).fallbacksDefault()
        b
            // short spoken answers: low effort is fast and enough (thinking stays adaptive, Sonnet 5.5's default)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .systemOfBetaTextBlockParams(
                listOf(
                    BetaTextBlockParam.builder().text(req.persona).cacheControl(BetaCacheControlEphemeral.builder().build()).build(),
                    BetaTextBlockParam.builder().text(req.live).build(),
                ),
            )
        for ((fromUser, text) in Claude.alternate(req.history)) {
            if (fromUser) b.addUserMessage(text) else b.addAssistantMessage(text)
        }
        val parts = mutableListOf<BetaContentBlockParam>()
        // The screenshot travels only with this request; history never keeps images (spec §10.4).
        req.imageJpegBase64?.let { data ->
            parts += BetaContentBlockParam.ofImage(
                BetaImageBlockParam.builder()
                    .source(BetaBase64ImageSource.builder().mediaType(BetaBase64ImageSource.MediaType.IMAGE_JPEG).data(data).build())
                    .build(),
            )
        }
        parts += BetaContentBlockParam.ofText(BetaTextBlockParam.builder().text(req.userText).build())
        b.addUserMessageOfContentBlockParams(parts)
        if (req.useSearch) {
            b.addTool(
                BetaWebSearchTool20260209.builder()
                    .maxUses(2)
                    .userLocation(BetaUserLocation.builder().city("Tunis").country("TN").timezone("Africa/Tunis").build())
                    .build(),
            )
        }
        return b.build()
    }

    override fun send(key: String, req: Claude.Request): Claude.Answer {
        val c = client(key)
        var params = params(req)
        // A 400 about the fallback beta (not offered to this account) must not cost Ayman his main brain:
        // the same message once more without it (like Gemini's 400-thinking retry).
        val first = try {
            call { c.beta().messages().create(params) }
        } catch (e: Claude.Error) {
            if (e.kind != Claude.Fail.BAD_REQUEST || !e.detail.contains("fallback", ignoreCase = true) || fallbackRefused) throw e
            fallbackRefused = true
            params = params(req, withFallback = false)
            call { c.beta().messages().create(params) }
        }
        val text = StringBuilder()
        var searches = 0
        var stop = ""
        for (round in 0..1) {
            val msg = if (round == 0) first else call { c.beta().messages().create(params) }
            for (block in msg.content()) {
                block.text().ifPresent { text.append(it.text()) }
                if (block.isServerToolUse()) searches++
            }
            val reason = msg.stopReason().orElse(null)
            stop = reason?.toString().orEmpty()
            if (reason == BetaStopReason.REFUSAL) return Claude.Answer(true, "", searches, stop)
            // a long web search can pause the turn: send it back once and Claude carries on where it stopped
            if (reason == BetaStopReason.PAUSE_TURN && round == 0) {
                params = params.toBuilder().addMessage(msg).build()
                continue
            }
            break
        }
        return Claude.Answer(false, text.toString().trim(), searches, stop)
    }

    override fun check(key: String): String = call { client(key).models().retrieve(Claude.MODEL).displayName() }

    override fun ping(key: String) {
        call {
            client(key).beta().messages().create(
                MessageCreateParams.builder().model(Claude.MODEL).maxTokens(64)
                    .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                    .addUserMessage("Reply with one word.").build(),
            )
        }
    }

    private fun <T> call(f: () -> T): T = try { f() } catch (e: Throwable) { throw map(e) }

    private fun map(e: Throwable): Claude.Error = when (e) {
        is Claude.Error -> e
        is AnthropicServiceException -> {
            val msg = e.message.orEmpty()
            Claude.Error(Claude.classify(e.statusCode(), msg), "HTTP ${e.statusCode()} ${msg.take(120).replace('\n', ' ')}".trim())
        }
        else -> {
            val causes = generateSequence(e) { it.cause }.take(6).toList()
            when {
                causes.any { it is UnknownHostException } -> Claude.Error(Claude.Fail.OFFLINE, "offline")
                causes.any { it is InterruptedIOException } -> Claude.Error(Claude.Fail.TIMEOUT, "timeout")
                e is AnthropicIoException -> Claude.Error(Claude.Fail.OFFLINE, "io ${e.cause?.javaClass?.simpleName ?: ""}".trim())
                else -> Claude.Error(Claude.Fail.SERVER, e.javaClass.simpleName)
            }
        }
    }
}
