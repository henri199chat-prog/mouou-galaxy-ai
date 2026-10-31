package com.glteam.teambubbles

import org.json.JSONArray

/** Short-term memory (spec §10.4): last 20 turns, forgotten after 60 minutes of silence, no images. */
class ConvoMemory(private val clock: () -> Long = System::currentTimeMillis) {
    private val turns = ArrayDeque<Turn>()

    fun history(): List<Turn> {
        val last = turns.lastOrNull() ?: return emptyList()
        if (clock() - last.atMs > SILENCE_MS) turns.clear()
        return turns.toList()
    }

    fun add(fromUser: Boolean, text: String) {
        history() // applies the silence rule first
        turns.addLast(Turn(fromUser, text, clock()))
        while (turns.size > MAX_TURNS) turns.removeFirst()
    }

    fun clear() = turns.clear()

    companion object {
        const val MAX_TURNS = 20
        const val SILENCE_MS = 60 * 60 * 1000L
    }
}

/** Long-term facts about Ayman (spec §10.4): 3–200 characters, de-duplicated, at most 30, oldest dropped. */
class Facts(initial: List<String> = emptyList()) {
    private val items = initial.filter { it.trim().length in MIN..MAX }.distinctBy { it.trim().lowercase() }.takeLast(LIMIT).toMutableList()

    val all: List<String> get() = items.toList()

    /** Returns true when a new fact was stored. */
    fun remember(raw: String): Boolean {
        val fact = raw.trim()
        if (fact.length !in MIN..MAX) return false
        if (items.any { it.equals(fact, ignoreCase = true) }) return false
        items += fact
        while (items.size > LIMIT) items.removeAt(0)
        return true
    }

    fun clear() = items.clear()

    fun toJson(): String = JSONArray(items).toString()

    companion object {
        const val MIN = 3
        const val MAX = 200
        const val LIMIT = 30

        fun fromJson(json: String?): Facts {
            if (json.isNullOrBlank()) return Facts()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return Facts()
            return Facts((0 until arr.length()).map { arr.optString(it) })
        }
    }
}
