package com.glteam.teambubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiPartsTest {
    @Test fun modelOrderingFollowsTheSpec() {
        val models = Gemini.orderModels(BrainTest.MODELS_LIST)
        assertEquals(listOf("gemini-2.5-pro", "gemini-3.8-flash", "gemini-2.5-flash", "gemini-flash-latest", "gemini-3.5-flash-lite"), models)
    }

    @Test fun pastedKeysAreCleaned() {
        val key = "AIza" + "A1b2_C3d4-".repeat(3) + "e5f6g"
        assertEquals(key, KeySanitizer.clean("GEMINI_API_KEY=\"$key\"\n"))
        assertEquals(key, KeySanitizer.clean("​ $key ﻿"))
        assertTrue(KeySanitizer.looksValid(key))
        // Arabic clipboard: invisible bidi marks around the copied key (Ayman's 2.3.0 report).
        assertEquals(key, KeySanitizer.clean("\u200F\u202B$key\u202C\u200E"))
        assertEquals(key, KeySanitizer.clean("\u2067$key\u2069 \n"))
        assertEquals(key, KeySanitizer.clean("gemini_api_key: $key"))
        assertFalse(KeySanitizer.looksValid("AIza123"))
    }

    @Test fun parserIsTolerant() {
        assertEquals("مرحبا", ReplyParser.parse("{\"reply\":\"مرحبا\"}").reply)
        assertEquals("مرحبا", ReplyParser.parse("```json\n{\"reply\":\"مرحبا\",\"emotion\":\"love\"}\n```").reply)
        assertEquals("مرحبا", ReplyParser.parse("هاو: {\"reply\":\"مرحبا\"} باي").reply)
        val plain = ReplyParser.parse("نتحوّل قطوس توا! [animal:cat] [mood:happy]")
        assertEquals("نتحوّل قطوس توا!", plain.reply)
        assertEquals("cat", plain.animal)
        assertEquals("happy", plain.mood)
    }

    @Test fun requestBodyHasSchemaOnlyWithoutSearch() {
        val withSearch = Gemini.requestBody("s", emptyList(), "q", "gemini-3.8-flash", useSearch = true, useSchema = true, useThinking = true)
        assertTrue(withSearch.contains("google_search") && !withSearch.contains("responseSchema"))
        assertTrue(withSearch.contains("\"thinkingLevel\":\"low\""))
        val noSearch = Gemini.requestBody("s", emptyList(), "q", "gemini-2.5-flash", useSearch = false, useSchema = true, useThinking = true)
        assertTrue(noSearch.contains("responseSchema") && noSearch.contains("\"thinkingBudget\":512"))
        assertTrue(noSearch.contains("\"maxOutputTokens\":4096"))
    }

    @Test fun memoryKeeps20TurnsAndForgetsAfterAnHourOfSilence() {
        var now = 0L
        val m = ConvoMemory { now }
        repeat(25) { m.add(it % 2 == 0, "t$it"); now += 1000 }
        assertEquals(20, m.history().size)
        assertEquals("t5", m.history().first().text)
        now += ConvoMemory.SILENCE_MS + 1
        assertTrue(m.history().isEmpty())
    }

    @Test fun factsAreDedupedBoundedAndPersisted() {
        val f = Facts()
        assertTrue(f.remember("يحب الكسكسي"))
        assertFalse(f.remember("  يحب الكسكسي "))
        assertFalse(f.remember("ab"))
        repeat(40) { f.remember("fact number $it") }
        assertEquals(Facts.LIMIT, f.all.size)
        assertEquals(f.all, Facts.fromJson(f.toJson()).all)
    }

    @Test fun userTurnCarriesScreenOffTagUntilStep16() {
        assertTrue(SystemPrompt.userTurn("شنوة تشوف؟", screenAvailable = false).endsWith(SystemPrompt.SCREEN_OFF_TAG))
        assertEquals("q", SystemPrompt.userTurn("q", screenAvailable = true))
    }
}
