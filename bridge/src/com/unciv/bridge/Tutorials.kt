package com.unciv.bridge

import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.models.TutorialTrigger
import com.unciv.models.ruleset.Event
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Unciv's tutorials, both kinds:
 *
 * - **Popups** (Tutorials.json "steps"): shown once each when their moment comes - the same triggers and tests as
 *   Unciv's WorldScreen (on every update, and at the start of each turn), or when the client says it opened the
 *   matching screen ([show]: policies, promotions). They come to the client as alerts of type "Tutorial", one step
 *   at a time, after the game's own alerts; answering the last step marks the tutorial seen.
 * - **Tasks** (the "Tutorial Task: [...]" events in the ruleset, Unciv's floating card at the top of the map):
 *   [task] is the first one available now, with Unciv's own conditions. Doing the thing completes it - the bridge
 *   marks the ones it sees happen ([done]), the client the ones it does itself (entering the city view...).
 *
 * Off unless the client says otherwise (setSettings showTutorials). What's been seen and done is kept in
 * ~/.unciv-vr/tutorials.json, so it survives restarts like Unciv's own settings file does.
 */
object Tutorials {
    private val file = File(BridgeMain.dataDir, "tutorials.json")
    private val settings get() = UncivGame.Current.settings

    /** Popups waiting their turn, oldest first, and which step of the first one is up */
    private val queue = LinkedHashSet<TutorialTrigger>()
    private var step = 0
    private var lastTurnChecked = -1
    private var savedSnapshot = ""

    var enabled: Boolean
        get() = settings.showTutorials
        set(value) {
            settings.showTutorials = value
            if (!value) { queue.clear(); step = 0 }
        }

    fun load() {
        try {
            if (!file.isFile) return
            val json = Json.parseToJsonElement(file.readText()).jsonObject
            json["shown"]?.jsonArray?.forEach { settings.tutorialsShown.add(it.jsonPrimitive.content) }
            json["tasksCompleted"]?.jsonArray?.forEach { settings.tutorialTasksCompleted.add(it.jsonPrimitive.content) }
            savedSnapshot = snapshot()
        } catch (ex: Exception) {
            System.err.println("Couldn't read ${file.path}: $ex")
        }
    }

    private fun snapshot() = settings.tutorialsShown.sorted().joinToString("|") + "#" + settings.tutorialTasksCompleted.sorted().joinToString("|")

    /** Writes the file when something changed (Unciv's own logic completes some tasks by itself, e.g. meeting a civ). */
    private fun saveIfChanged() {
        val now = snapshot()
        if (now == savedSnapshot) return
        savedSnapshot = now
        try {
            file.parentFile.mkdirs()
            file.writeText(buildJsonObject {
                putJsonArray("shown") { for (name in settings.tutorialsShown.sorted()) add(name) }
                putJsonArray("tasksCompleted") { for (name in settings.tutorialTasksCompleted.sorted()) add(name) }
            }.toString())
        } catch (ex: Exception) {
            System.err.println("Couldn't write ${file.path}: $ex")
        }
    }

    /** Forget what's been seen and done (Settings > Reset tutorials). */
    fun reset() {
        settings.tutorialsShown.clear()
        settings.tutorialTasksCompleted.clear()
        queue.clear()
        step = 0
        lastTurnChecked = -1
        saveIfChanged()
    }

    /** A tutorial task done ("Move unit", "Enter city screen"...). */
    fun done(task: String) {
        if (settings.tutorialTasksCompleted.add(task)) saveIfChanged()
    }

    /** Queues a popup unless tutorials are off, it's been seen, or [test] says it isn't time (BaseScreen.displayTutorial). */
    private fun display(trigger: TutorialTrigger, test: (() -> Boolean)? = null) {
        if (!enabled || trigger.name in settings.tutorialsShown || trigger in queue) return
        val passes = try { test == null || test() } catch (ex: Exception) {
            System.err.println("Tutorial ${trigger.name}: $ex")
            false
        }
        if (passes) queue.add(trigger)
    }

    /** The client opened a screen with a tutorial of its own: "CultureAndPolicies" (policies), "Experience" (promotions)... */
    fun show(name: String) {
        val trigger = TutorialTrigger.entries.firstOrNull { it.name == name } ?: throw BridgeException("No tutorial '$name'")
        display(trigger)
    }

    /** WorldScreen.displayTutorialsOnUpdate, and showTutorialsOnNextTurn once per turn. */
    fun check(game: GameInfo, civ: Civilization) {
        if (!enabled || civ.isSpectator()) return
        display(TutorialTrigger.Introduction)
        display(TutorialTrigger.EnemyCityNeedsConqueringWithMeleeUnit) {
            civ.diplomacy.values.asSequence()
                .filter { it.diplomaticStatus == DiplomaticStatus.War }
                .map { it.otherCiv }
                .flatMap { it.cities.asSequence() }
                .filter { it.health == 1 }
                .flatMap { it.getCenterTile().getTilesInDistance(2) }
                .any { tile -> tile.getUnits().any { it.civ == civ } }
        }
        display(TutorialTrigger.AfterConquering) { civ.cities.any { it.hasJustBeenConquered } }
        display(TutorialTrigger.InjuredUnits) { civ.units.getCivUnits().any { it.health < 100 } }
        display(TutorialTrigger.Workers) {
            civ.units.getCivUnits().any { it.cache.hasUniqueToBuildImprovements && it.isCivilian() && !it.isGreatPerson() }
        }
        // Unciv shows this one when the diplomacy button first appears - you know another civ
        display(TutorialTrigger.OtherCivEncountered) { civ.getKnownCivs().any { !it.isBarbarian } }
        // A city button shows the blockade symbol (Unciv shows this when you click it)
        display(TutorialTrigger.CityBlockade) { civ.cities.any { it.isBlockaded() } }

        if (game.turns == lastTurnChecked || !civ.isCurrentPlayer()) return
        lastTurnChecked = game.turns
        display(TutorialTrigger.SlowStart)
        display(TutorialTrigger.CityExpansion) { civ.cities.any { it.expansion.tilesClaimed() > 0 } }
        display(TutorialTrigger.BarbarianEncountered) { civ.viewableTiles.any { tile -> tile.getUnits().any { it.civ.isBarbarian } } }
        display(TutorialTrigger.RoadsAndRailroads) { civ.cities.size > 2 }
        display(TutorialTrigger.Happiness) { civ.getHappiness() < 5 }
        display(TutorialTrigger.Unhappiness) { civ.getHappiness() < 0 }
        display(TutorialTrigger.GoldenAge) { civ.goldenAges.isGoldenAge() }
        display(TutorialTrigger.IdleUnits) { game.turns >= 50 && settings.checkForDueUnits }
        display(TutorialTrigger.ContactMe) { game.turns >= 100 }
        val resources = civ.detailedCivResources.asSequence().filter { it.origin == "All" }
        display(TutorialTrigger.LuxuryResource) { resources.any { it.resource.resourceType == ResourceType.Luxury } }
        display(TutorialTrigger.StrategicResource) { resources.any { it.resource.resourceType == ResourceType.Strategic } }
        display(TutorialTrigger.EnemyCity) {
            civ.getKnownCivs().filter { civ.isAtWarWith(it) }.flatMap { it.cities.asSequence() }.any { civ.hasExplored(it.getCenterTile()) }
        }
        display(TutorialTrigger.Embarking) { civ.hasUnique(UniqueType.LandUnitEmbarkation) }
        display(TutorialTrigger.NaturalWonders) { civ.naturalWonders.size > 0 }
        display(TutorialTrigger.WeLoveTheKingDay) { civ.cities.any { it.demandedResource != "" } }
    }

    private fun tutorialName(trigger: TutorialTrigger) = trigger.value.replace('_', ' ').trimStart()

    private fun steps(game: GameInfo, trigger: TutorialTrigger): List<String> =
        game.ruleset.tutorials[tutorialName(trigger)]?.steps.orEmpty()

    /** The popup up now (if any), as an alert after the game's own. */
    fun alerts(game: GameInfo): List<JsonObject> {
        if (!enabled) return emptyList()
        while (queue.isNotEmpty()) {
            val trigger = queue.first()
            val steps = steps(game, trigger)
            if (step >= steps.size) {   // nothing (left) to show: done with it
                finish(trigger)
                continue
            }
            val last = step == steps.size - 1
            return listOf(buildJsonObject {
                put("key", "Tutorial|${trigger.name}|$step")
                put("type", "Tutorial")
                put("title", Civilopedia.text(tutorialName(trigger)))
                put("text", Civilopedia.text(steps[step]).trim('\n'))
                put("needsAnswer", false)
                put("tutorial", tutorialName(trigger))
                put("step", step + 1)
                put("steps", steps.size)
                putJsonArray("options") {
                    addJsonObject {
                        put("id", if (last) "close" else "next")
                        put("label", Lang.t(if (last) "Got it" else "Next"))
                        put("enabled", true)
                    }
                }
            })
        }
        return emptyList()
    }

    private fun finish(trigger: TutorialTrigger) {
        queue.remove(trigger)
        step = 0
        settings.tutorialsShown.add(trigger.name)
        saveIfChanged()
    }

    /** Answering a tutorial alert ("Tutorial|<trigger>|<step>"): on to the next step, or done. */
    fun answer(key: String): JsonObject {
        val parts = key.split('|')
        val trigger = queue.firstOrNull()
        if (parts.size < 3 || trigger == null || trigger.name != parts[1]) throw BridgeException("That tutorial is no longer showing")
        step++
        return buildJsonObject { put("message", "") }
    }

    /** The current tutorial task (Unciv's floating card), or null. */
    fun task(game: GameInfo, civ: Civilization): JsonObject? {
        if (!enabled || civ.isDefeated() || civ.isSpectator()) return null
        try {
            if ("Create a trade route" !in settings.tutorialTasksCompleted &&
                civ.cache.citiesConnectedToCapitalToMediums.any { it.key.civ == civ })
                done("Create a trade route")
        } catch (_: Exception) {}
        saveIfChanged()
        val context = civ.state
        val event = game.ruleset.events.values.firstOrNull {
            it.presentation == Event.Presentation.Floating && try { it.isAvailable(context) } catch (_: Exception) { false }
        } ?: return null
        return buildJsonObject {
            put("name", event.name)
            put("task", Regex("""\[(.*)]""").find(event.name)?.groupValues?.get(1) ?: event.name)
            put("lines", Civilopedia.lines(event.civilopediaText))
            putJsonArray("choices") {
                event.choices.forEachIndexed { index, choice ->
                    if (try { choice.isAvailable(context) } catch (_: Exception) { false }) addJsonObject {
                        put("index", index)
                        put("text", Civilopedia.text(choice.text))
                    }
                }
            }
        }
    }

    /** Picks a choice on the task card ("Got it" on "Meet another civilization"): runs its uniques, as Unciv's button. */
    fun chooseTask(game: GameInfo, civ: Civilization, name: String, index: Int) {
        val event = game.ruleset.events[name] ?: throw BridgeException("No task '$name'")
        val choice = event.choices.getOrNull(index) ?: throw BridgeException("No choice $index on '$name'")
        choice.triggerChoice(civ)
        saveIfChanged()
    }
}
