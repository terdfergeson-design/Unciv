package com.unciv.bridge

import com.badlogic.gdx.Gdx
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.models.metadata.LocaleCode
import com.unciv.models.translations.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The language the game talks in: Unciv's own translations (android/assets/jsons/translations, ~50 languages by
 * Unciv's translators), the same files and the same `tr()` Unciv's screens use.
 *
 * - Text the bridge sends for people to read goes through [t]: Unciv's sentences are written the way Unciv's
 *   translation files have them ("What would you like to do with the city of [Rome]?"), so they come out in the
 *   chosen language with their parts translated too; text Unciv has no translation for stays English.
 * - Names stay English in the protocol (they're also what commands and icons go by); [dictionary] gives the client
 *   every plain Unciv translation (unit, building, tech, nation, city names, Unciv's button and screen words...) to
 *   show them in the chosen language.
 */
object Lang {
    private val game get() = UncivGame.Current

    val language: String get() = game.settings.language

    val isEnglish get() = language == Constants.english

    /** Unciv's translation of [raw] in the chosen language - placeholders filled, {parts} and [parameters] translated,
     *  icons left out - or plain English without Unciv's brackets if that fails. */
    /** In English it's what the bridge always sent: the text with Unciv's brackets taken out. Translate only once:
     *  translating translated text would garble it (numbers get reformatted, for one). */
    fun t(raw: String): String =
        if (raw.isEmpty() || isEnglish) Gameplay.stripBrackets(raw)
        else try { raw.tr(hideIcons = true) } catch (_: Exception) { Gameplay.stripBrackets(raw) }

    /** Text of several lines (a unit's or nation's uniques...), each line translated on its own as Unciv does. */
    fun lines(raw: String): String = raw.split('\n').joinToString("\n") { t(it) }

    /** Several of Unciv's sentences as one text to translate later with [t]: each in {}, as Unciv's translation
     *  files have them one by one. */
    fun sentences(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString(" ") { "{$it}" }

    /** Loads Unciv's percentages of how complete each language is (shown in the language list). */
    fun init() {
        try { game.translations.loadPercentageCompleteOfLanguages() } catch (ex: Exception) { System.err.println("Languages: $ex") }
    }

    /** Switches to [newLanguage] (Unciv's name for it: "German", "Simplified_Chinese"...), loading its file. */
    fun set(newLanguage: String) {
        val wanted = newLanguage.ifEmpty { Constants.english }
        if (wanted != Constants.english && LocaleCode.find(wanted) == null) throw BridgeException("No language '$wanted'")
        if (wanted == language) return
        game.settings.language = wanted
        if (wanted != Constants.english && game.translations.values.none { it.containsKey(wanted) }) {
            val start = System.currentTimeMillis()
            game.translations.tryReadTranslationForCurrentLanguage()
            println("Loaded the $wanted translation in ${System.currentTimeMillis() - start}ms")
        }
    }

    /** The IETF tag of [lang] ("de-DE"), for the client's own text system. */
    fun tag(lang: String = language): String =
        if (lang == Constants.english) "en" else LocaleCode.find(lang)?.languageTag ?: "en"

    /** getLanguages: every language Unciv has a translation file for, with how complete it is, and the current one. */
    fun languages(): JsonObject {
        val percentages = game.translations.percentCompleteOfLanguages
        // These catalogs use logical Unicode and require an RTL-capable frontend (Godot).
        // Keep them disabled in LocaleCode's stock Unciv UI language list.
        val names = (LocaleCode.getSupportedLanguages() + sequenceOf("Arabic", "Hebrew"))
            .distinct()
            .filter { it != Constants.english } // The source language is added once below at 100%.
            .filter { Gdx.files.internal("jsons/translations/$it.properties").exists() }
            .toList()
        return buildJsonObject {
            put("language", language)
            putJsonArray("languages") {
                addJsonObject { put("name", Constants.english); put("label", "English"); put("tag", "en"); put("percent", 100) }
                for (name in names.sortedBy { it.replace('_', ' ') }) addJsonObject {
                    put("name", name)
                    put("label", when (name) {
                        "Arabic" -> "Arabic / العربية"
                        "Hebrew" -> "Hebrew / עברית"
                        else -> name.replace('_', ' ')
                    })
                    put("tag", tag(name))
                    put("percent", percentages[name] ?: 0)
                }
            }
        }
    }

    /**
     * getTranslations: the chosen language's plain translations - every entry of Unciv's file without [placeholders],
     * English -> translated (empty for English). The client shows names and Unciv's words through it.
     */
    fun dictionary(): JsonObject = buildJsonObject {
        put("language", language)
        put("tag", tag())
        putJsonObject("entries") {
            if (!isEnglish) {
                for (entry in game.translations.values) {
                    val key = entry.entry
                    if ('[' in key || '{' in key) continue
                    val value = entry[language] ?: continue
                    if (value.isEmpty() || value == key) continue
                    put(key, value)
                }
            }
        }
    }
}
