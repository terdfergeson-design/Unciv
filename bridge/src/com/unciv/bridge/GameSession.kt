package com.unciv.bridge

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.logic.map.mapgenerator.MapResourceSetting
import com.unciv.models.ruleset.Speed
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.stats.Stat
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UiUnitActionType
import com.unciv.view.AttackableTileView
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.concurrent.thread

/**
 * Holds the one game the bridge is running and implements every protocol method.
 * All calls arrive already serialized (one at a time) from [BridgeServer].
 *
 * Reads go through Unciv's View API ([GameView] etc.), so the client only learns what
 * the human player is allowed to see. See bridge/PROTOCOL.md for the method list.
 */
class GameSession {
    companion object {
        const val RANDOM = "Random"
        const val AUTOSAVE = "Autosave"

        /** What a new game uses when the client doesn't say otherwise. */
        val DEFAULT_SETUP: Map<String, JsonElement> = mapOf(
            "civ" to JsonPrimitive(RANDOM),
            "ruleset" to JsonPrimitive(BaseRuleset.Civ_V_GnK.fullName),
            "difficulty" to JsonPrimitive("Prince"),
            "speed" to JsonPrimitive(Speed.DEFAULT),
            "startingEra" to JsonPrimitive("Ancient era"),
            "aiPlayers" to JsonPrimitive(3),
            "cityStates" to JsonPrimitive(4),
            "mapSize" to JsonPrimitive("Tiny"),
            "mapShape" to JsonPrimitive(MapShape.hexagonal),
            "mapType" to JsonPrimitive(MapType.pangaea),
            "mapResources" to JsonPrimitive(MapResourceSetting.default.label),
            "noRuins" to JsonPrimitive(false),
            "noNaturalWonders" to JsonPrimitive(false),
            "worldWrap" to JsonPrimitive(false),
            "noBarbarians" to JsonPrimitive(false),
            "ragingBarbarians" to JsonPrimitive(false),
            "oneCityChallenge" to JsonPrimitive(false),
            "noCityRazing" to JsonPrimitive(false),
            "nuclearWeapons" to JsonPrimitive(true),
            "espionage" to JsonPrimitive(false),
        )
    }

    private var gameInfo: GameInfo? = null

    // Delta updates for getState: what each tile looked like when last sent, and a revision number
    // the client hands back as "since" to get only the tiles that changed after that.
    private var sentTiles: Array<JsonObject?> = emptyArray()
    private var stateRevision = 0

    /** Off for test games (newGame with remember: false), so they don't replace the player's autosave. */
    private var autosaveEnabled = true

    fun handle(method: String, params: JsonObject): JsonElement = when (method) {
        "ping" -> ping()
        "listOptions" -> listOptions(params)
        "newGame" -> newGame(params)
        "loadGame" -> loadGame(params)
        "saveGame" -> saveGame(params)
        "getState" -> getState(params)
        "getSetup" -> getSetup()
        "getPolicies" -> getPolicies()
        "describeTech" -> StateWriter.techDescription(viewer(), params.requireStr("tech"))
        "getYields" -> {
            val positions = (params["tiles"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { element ->
                val pair = element as? JsonArray ?: return@mapNotNull null
                val x = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                val y = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                HexCoord(x, y)
            }
            StateWriter.yields(game(), viewer(), positions)
        }
        "adoptPolicy" -> adoptPolicy(params)
        "getDiplomacy" -> getDiplomacy()
        "declareWar" -> declareWar(params)
        "offerPeace" -> offerPeace(params)
        "getTradeOptions" -> getTradeOptions(params)
        "evaluateTrade" -> evaluateTrade(params)
        "proposeTrade" -> proposeTrade(params)
        "getTile" -> {
            val position = HexCoord(params.requireInt("x"), params.requireInt("y"))
            if (position !in game().tileMap) throw BridgeException("($position) is off the map")
            StateWriter.tileDetails(game(), viewer(), game().tileMap[position])
        }
        "listSaves" -> listSaves()
        "getRuleset" -> StateWriter.ruleset(game())
        "getUnit" -> getUnit(params)
        "moveUnit" -> moveUnit(params)
        "previewMove" -> previewMove(params)
        "unitAction" -> unitAction(params)
        "attack" -> attack(params)
        "buildImprovement" -> buildImprovement(params)
        "getCity" -> getCity(params)
        "setProduction" -> setProduction(params)
        "removeFromQueue" -> removeFromQueue(params)
        "buyConstruction" -> buyConstruction(params)
        "cityTile" -> cityTile(params)
        "setCityFocus" -> cityChange(params) { city ->
            val name = params.requireStr("focus")
            val focus = CityFocus.entries.firstOrNull { it.name == name } ?: throw BridgeException("Unknown focus '$name'")
            city.trySetCityFocus(focus)
        }
        "setAvoidGrowth" -> cityChange(params) { city ->
            if ((params.bool("avoid") ?: !city.avoidGrowth) != city.avoidGrowth) city.tryToggleAvoidGrowth() else true
        }
        "resetCitizens" -> cityChange(params) { city -> city.tryReassignPopulation(resetLocked = true) }
        "setManualSpecialists" -> cityChange(params) { city ->
            if (params.bool("manual") ?: true) city.tryEnableManualSpecialists() else city.tryDisableManualSpecialists()
        }
        "setSpecialist" -> cityChange(params) { city ->
            val name = params.requireStr("specialist")
            val delta = params.int("delta") ?: 1
            val max = city.getMaxSpecialists()[name]
            val assigned = city.getNewSpecialists()[name]
            when {
                delta > 0 && assigned >= max -> throw BridgeException("No free $name slots - build more buildings that have them")
                delta > 0 && city.getFreePopulation() <= 0 -> throw BridgeException("No free citizens - take one off a tile first")
                delta < 0 && assigned <= 0 -> throw BridgeException("No $name to take off")
                delta > 0 -> city.tryAssignSpecialist(name)
                else -> city.tryUnassignSpecialist(name)
            }
        }
        "moveInQueue" -> cityChange(params) { city ->
            val index = params.requireInt("index")
            if (index !in city.constructions.constructionQueue.indices) throw BridgeException("No queue entry $index")
            when (params.str("to") ?: "up") {
                "up" -> city.tryRaisePriority(index) != null
                "down" -> city.tryLowerPriority(index) != null
                "top" -> city.tryMoveEntryToTop(index)
                "end" -> city.tryMoveEntryToEnd(index)
                else -> throw BridgeException("'to' must be up, down, top or end")
            }.also { city.tryReassignPopulation() }
        }
        "sellBuilding" -> cityChange(params) { city ->
            val name = params.requireStr("building")
            val building = city.getBuiltBuildings().firstOrNull { it.name == name } ?: throw BridgeException("${city.name} has no $name")
            if (!building.isSellable()) throw BridgeException("$name can't be sold")
            if (city.hasFreeBuilding(building)) throw BridgeException("$name was free - it can't be sold")
            if (city.hasSoldBuildingThisTurn()) throw BridgeException("${city.name} already sold a building this turn")
            city.trySellBuilding(building)
        }
        "setRazing" -> cityChange(params, allowPuppet = true) { city ->
            val raze = params.bool("raze") ?: true
            if (raze && !city.canBeDestroyed()) throw BridgeException("${city.name} can't be razed")
            city.trySetRazing(raze)
        }
        "annexCity" -> cityChange(params, allowPuppet = true) { city ->
            if (!city.isPuppet()) throw BridgeException("${city.name} isn't a puppet")
            city.tryAnnexCity()
        }
        "getOverview" -> OverviewWriter.overview(game(), viewer(), params.str("tab") ?: "cities")
        "upgradeUnit" -> {
            if (!viewer().isCurrentPlayer()) throw BridgeException("It isn't your turn")
            OverviewWriter.upgradeUnit(viewer(), params.requireInt("unitId"), params.str("to"))
            OverviewWriter.overview(game(), viewer(), "units")
        }
        "setGoldToScience" -> {
            val civView = GameView(game(), viewer()).civView
            val percent = (params.str("percent")?.toFloatOrNull() ?: throw BridgeException("percent (0 to 1) is required")).coerceIn(0f, 1f)
            if (!game().ruleset.modOptions.hasUnique(com.unciv.models.ruleset.unique.UniqueType.ConvertGoldToScience))
                throw BridgeException("This ruleset doesn't let you convert gold to science")
            civView.trySetGoldPercentConvertedToScience(percent)
            civView.tryUpdateAllCityStats()
            OverviewWriter.overview(game(), viewer(), "stats")
        }
        "renameCity" -> {
            val target = findOwnCity(params)
            val newName = params.requireStr("newName").trim()
            if (newName.isEmpty()) throw BridgeException("A city needs a name")
            if (game().getCities().any { it.name == newName && it !== target })
                throw BridgeException("There's already a city called $newName")
            cityChange(params) { city -> city.tryRenameCity(newName) }
        }
        "renameUnit" -> {
            val unit = findOwnUnit(params)
            val newName = params.str("newName")?.trim()
            // Like Unciv's rename popup: an empty name goes back to the unit's ordinary name
            GameView(game(), viewer()).getMapUnitView(unit).trySetInstanceName(if (newName.isNullOrEmpty()) null else newName)
            getUnit(params)
        }
        "describeConstruction" -> {
            val city = findOwnCity(params)
            StateWriter.constructionDescription(GameView(game(), viewer()).getCityView(city), params.requireStr("construction"))
        }
        // v16: closing the gameplay gaps
        "answerAlert" -> if (params.requireStr("key").startsWith("Tutorial|")) Tutorials.answer(params.requireStr("key")) else {
            val result = Alerts.answer(game(), viewer(), params.requireStr("key"), params.requireStr("option"))
            viewer().updateStatsForNextTurn()
            result
        }
        "dismissBlocker" -> { Gameplay.dismiss(viewer(), params.requireStr("type")); turnStatus() }
        "getGreatPersonChoices" -> Gameplay.greatPersonChoices(viewer())
        "chooseGreatPerson" -> {
            requireTurn()
            Gameplay.chooseGreatPerson(viewer(), params.requireStr("unit"))
            turnStatus()
        }
        "getVoteChoices" -> Gameplay.voteChoices(viewer())
        "vote" -> { requireTurn(); Gameplay.vote(viewer(), params.str("civ")); turnStatus() }
        "getReligionChoices" -> ReligionPicker.choices(viewer())
        "chooseReligion" -> { requireTurn(); ReligionPicker.choose(viewer(), params) }
        "getVictory" -> Gameplay.victory(game(), viewer())
        "oneMoreTurn" -> { Gameplay.oneMoreTurn(game()); Gameplay.victory(game(), viewer()) }
        "diplomacyAction" -> {
            val message = DiplomacyActions.act(viewer(), findKnownCiv(params), params.requireStr("action"), params)
            buildJsonObject {
                put("message", Lang.t(message))
                put("diplomacy", StateWriter.diplomacy(viewer()))
            }
        }
        "cityAttack" -> cityAttack(params)
        "getEspionage" -> Espionage.overview(viewer())
        "moveSpy" -> { requireTurn(); Espionage.move(viewer(), params) }
        "stageCoup" -> { requireTurn(); Espionage.coup(viewer(), params) }
        "debug" -> DebugCommands.run(game(), viewer(), params)
        "getTechs" -> StateWriter.techs(viewer())
        "setResearch" -> setResearch(params)
        "promote" -> promote(params)
        "setSettings" -> setSettings(params)
        // v24: languages
        "getLanguages" -> Lang.languages()
        // v26: Unciv's default music track (Options > Sound > Download music)
        "downloadMusic" -> Music.downloadDefault()
        "getTranslations" -> Lang.dictionary()
        "endTurn" -> endTurn()
        // v22: the Civilopedia and the tutorials
        "getCivilopedia" -> Civilopedia.categories(gameInfo)
        "getCivilopediaEntry" -> Civilopedia.entry(gameInfo, params.requireStr("link"))
        "searchCivilopedia" -> Civilopedia.search(gameInfo, params.requireStr("text"))
        "showTutorial" -> { Tutorials.show(params.requireStr("name")); turnStatus() }
        "tutorialTaskDone" -> { Tutorials.done(params.requireStr("task")); turnStatus() }
        "chooseTutorialTask" -> {
            Tutorials.chooseTask(game(), viewer(), params.requireStr("name"), params.requireInt("index"))
            turnStatus()
        }
        else -> throw BridgeException("Unknown method '$method'")
    }

    // region helpers

    private fun game(): GameInfo = gameInfo ?: throw BridgeException("No game running - call newGame or loadGame first")

    /** The civ the client plays as: the current player if human, otherwise the first living human. */
    private fun viewer(): Civilization {
        val game = game()
        val current = game.getCurrentPlayerCivilization()
        if (current.isHuman()) return current
        return game.civilizations.firstOrNull { it.isHuman() && it.isAlive() } ?: current
    }

    private fun setGame(game: GameInfo) {
        gameInfo = game
        sentTiles = emptyArray()
        stateRevision++   // a client's old "since" no longer matches, so it gets the new map in full
        UncivGame.Current.gameInfo = game
    }

    private fun requireTurn() {
        if (!viewer().isCurrentPlayer()) throw BridgeException("It isn't your turn")
    }

    /** Unciv's "check for idle units before ending the turn" (setSettings checkForDueUnits) */
    private var checkForDueUnits = true

    /** What still has to be decided this turn, and the pending messages. Also part of getState. */
    private fun turnStatus(): JsonObject {
        val game = game()
        val civ = viewer()
        return buildJsonObject {
            putJsonArray("blockers") {
                for (blocker in Gameplay.blockers(game, civ, Alerts.countNeedingAnswer(game, civ), checkForDueUnits)) add(blocker)
            }
            Tutorials.check(game, civ)
            putJsonArray("alerts") {
                for (alert in Alerts.list(game, civ)) add(alert)
                for (alert in Tutorials.alerts(game)) add(alert)   // after the game's own
            }
            Tutorials.task(game, civ)?.let { put("tutorialTask", it) }
        }
    }

    private fun findOwnUnit(params: JsonObject): MapUnit {
        val id = params.requireInt("unitId")
        return viewer().units.getCivUnits().firstOrNull { it.id == id }
            ?: throw BridgeException("You have no unit with id $id")
    }

    /** One of your cities, by "name" or by its centre tile "x", "y". */
    private fun findOwnCity(params: JsonObject): City {
        val name = params.str("name")
        val x = params.int("x")
        val y = params.int("y")
        return viewer().cities.firstOrNull {
            (name != null && it.name == name) || (x != null && y != null && it.location == HexCoord(x, y))
        } ?: throw BridgeException("You have no city ${name ?: "at ($x, $y)"}")
    }

    // endregion

    /**
     * params: since (optional) - the revision from a previous getState. With it, "tiles" lists only
     * the tiles that changed since then ("full": false); without it (or if it's stale) every tile.
     */
    private fun getState(params: JsonObject): JsonElement {
        val game = game()
        val since = params.int("since")
        val tileCount = game.tileMap.tileList.size
        val full = since == null || since != stateRevision || sentTiles.size != tileCount
        if (sentTiles.size != tileCount) sentTiles = arrayOfNulls(tileCount)
        val state = StateWriter.state(game, viewer()) { index, json ->
            val changed = sentTiles[index] != json
            sentTiles[index] = json
            full || changed
        }
        stateRevision++
        val status = try { turnStatus() } catch (ex: Exception) {
            System.err.println("Turn status failed: $ex")
            JsonObject(emptyMap())
        }
        return JsonObject(state + status + mapOf(
            "revision" to JsonPrimitive(stateRevision),
            "full" to JsonPrimitive(full),
        ))
    }

    private fun ping(): JsonElement = buildJsonObject {
        put("protocol", BridgeMain.PROTOCOL_VERSION)
        put("uncivVersion", UncivGame.VERSION.text)
        put("gameRunning", gameInfo != null)
    }

    /** What newGame accepts: every setup option and its allowed values (for the given ruleset). */
    private fun listOptions(params: JsonObject): JsonElement {
        val rulesetName = params.str("ruleset") ?: BaseRuleset.Civ_V_GnK.fullName
        val ruleset = RulesetCache[rulesetName] ?: throw BridgeException("Unknown ruleset '$rulesetName'")
        return buildJsonObject {
            putJsonArray("rulesets") { BaseRuleset.entries.forEach { add(it.fullName) } }
            putJsonArray("nations") {
                ruleset.nations.values.filter { it.isMajorCiv }.sortedBy { it.name }.forEach {
                    addJsonObject {
                        put("name", it.name)
                        put("leader", it.leaderName)
                        put("outerColor", it.getOuterColor().toString())
                        put("innerColor", it.getInnerColor().toString())
                        put("uniqueName", it.uniqueName)
                        put("uniqueText", Lang.lines(it.uniqueText.ifEmpty { it.uniques.joinToString("\n") }))
                    }
                }
            }
            put("cityStatesAvailable", ruleset.nations.values.count { it.isCityState })
            putJsonArray("difficulties") { ruleset.difficulties.keys.forEach { add(it) } }
            putJsonArray("speeds") { ruleset.speeds.keys.forEach { add(it) } }
            putJsonArray("eras") { ruleset.eras.keys.forEach { add(it) } }
            putJsonArray("victoryTypes") { ruleset.selectableVictories().forEach { add(it.name) } }
            putJsonArray("mapSizes") { MapSize.Predefined.entries.forEach { add(it.name) } }
            putJsonArray("mapShapes") { MapShape.allValues.forEach { add(it) } }
            putJsonArray("mapTypes") { MapType.allValues.forEach { add(it) } }
            putJsonArray("mapResources") { MapResourceSetting.activeLabels().forEach { add(it) } }
        }
    }

    /** The setup used last time (what "Quick start" plays), or the defaults. Same fields newGame takes. */
    private fun getSetup(): JsonElement = buildJsonObject {
        val saved = try {
            if (BridgeMain.lastSetupFile.isFile) Json.parseToJsonElement(BridgeMain.lastSetupFile.readText()).jsonObject else null
        } catch (ex: Exception) {
            System.err.println("Ignoring unreadable ${BridgeMain.lastSetupFile}: $ex")
            null
        }
        put("isLastSetup", saved != null)
        // A remembered ruleset this bridge can't load (a mod, or another build's setup - the file is shared by every
        // checkout that uses ~/.unciv-vr) would leave the new-game setup with no nations or options: start from the
        // default rules instead, with a Random civ
        var kept = saved ?: emptyMap()
        val savedRuleset = (kept["ruleset"] as? JsonPrimitive)?.contentOrNull
        if (savedRuleset != null && (savedRuleset !in BaseRuleset.entries.map { it.fullName } || RulesetCache[savedRuleset] == null)) {
            System.err.println("Remembered setup: ruleset '$savedRuleset' isn't available here - using the default rules")
            kept = kept - "ruleset" - "civ" - "victoryTypes"
            put("droppedRuleset", savedRuleset)
        }
        put("setup", JsonObject(DEFAULT_SETUP + kept))
    }

    /**
     * Starts a new game. params, all optional (see getSetup for the defaults and listOptions for allowed values):
     * civ ("Random" or a nation), ruleset, difficulty, speed, startingEra, aiPlayers, cityStates, victoryTypes[],
     * mapSize, mapShape, mapType, mapResources, noRuins, noNaturalWonders, worldWrap, noBarbarians, ragingBarbarians,
     * oneCityChallenge, noCityRazing, nuclearWeapons, espionage, seed.
     * The choices are remembered for getSetup ("Quick start").
     */
    private fun newGame(params: JsonObject): JsonElement {
        val p = JsonObject(DEFAULT_SETUP + params)
        val rulesetName = p.str("ruleset") ?: BaseRuleset.Civ_V_GnK.fullName
        val ruleset = RulesetCache[rulesetName] ?: throw BridgeException("Unknown ruleset '$rulesetName'")
        val majorNations = ruleset.nations.values.filter { it.isMajorCiv }.map { it.name }
        val requestedCiv = p.str("civ") ?: RANDOM
        val civ = if (requestedCiv == RANDOM) majorNations.random() else requestedCiv
        if (civ !in majorNations) throw BridgeException("Unknown nation '$civ'")
        fun choice(key: String, allowed: Collection<String>): String {
            val value = p.requireStr(key)
            if (value !in allowed) throw BridgeException("Unknown $key '$value' - one of ${allowed.joinToString()}")
            return value
        }
        val difficulty = choice("difficulty", ruleset.difficulties.keys)
        val speed = choice("speed", ruleset.speeds.keys)
        val era = choice("startingEra", ruleset.eras.keys)
        val mapType = choice("mapType", MapType.allValues)
        val mapShape = choice("mapShape", MapShape.allValues)
        val mapResources = choice("mapResources", MapResourceSetting.activeLabels())
        val aiPlayers = (p.int("aiPlayers") ?: 3).coerceIn(1, majorNations.size - 1)
        val cityStates = (p.int("cityStates") ?: 4).coerceIn(0, ruleset.nations.values.count { it.isCityState })
        val selectable = ruleset.selectableVictories().map { it.name }
        val victories = (p["victoryTypes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it in selectable }?.takeIf { it.isNotEmpty() } ?: selectable

        val setup = GameSetupInfo()
        setup.gameParameters.apply {
            baseRuleset = rulesetName
            this.difficulty = difficulty
            this.speed = speed
            startingEra = era
            players = ArrayList<Player>().apply {
                add(Player(civ, PlayerType.Human))
                repeat(aiPlayers) { add(Player()) } // random AI nations
            }
            numberOfCityStates = cityStates
            minNumberOfCityStates = cityStates
            maxNumberOfCityStates = cityStates
            victoryTypes = ArrayList(victories)
            noBarbarians = p.bool("noBarbarians") ?: false
            ragingBarbarians = p.bool("ragingBarbarians") ?: false
            oneCityChallenge = p.bool("oneCityChallenge") ?: false
            noCityRazing = p.bool("noCityRazing") ?: false
            nuclearWeaponsEnabled = p.bool("nuclearWeapons") ?: true
            espionageEnabled = p.bool("espionage") ?: false
        }
        setup.mapParameters.apply {
            mapSize = MapSize(MapSize.Predefined.entries.firstOrNull { it.name == p.str("mapSize") }
                ?: throw BridgeException("Unknown map size - use ${MapSize.Predefined.entries.joinToString { it.name }}"))
            shape = mapShape
            type = mapType
            this.mapResources = mapResources
            noRuins = p.bool("noRuins") ?: false
            noNaturalWonders = p.bool("noNaturalWonders") ?: false
            worldWrap = p.bool("worldWrap") ?: false
            p.long("seed")?.let { seed = it }
        }

        val game = GameStarter.startNewGame(setup)
        setGame(game)
        autosaveEnabled = params.bool("remember") != false
        // Remember the choices (not the seed, and "Random" stays random) for Quick start - unless told not to (tests)
        if (params.bool("remember") != false) try {
            BridgeMain.lastSetupFile.parentFile.mkdirs()
            BridgeMain.lastSetupFile.writeText(JsonObject(p - "seed" - "remember" + ("civ" to JsonPrimitive(requestedCiv))).toString())
        } catch (ex: Exception) {
            System.err.println("Couldn't remember the setup: $ex")
        }
        return StateWriter.summary(game, viewer())
    }

    /** Saves in the bridge's own folder (newest first): name, modified (ms since 1970), autosave. */
    private fun listSaves(): JsonElement = buildJsonObject {
        put("folder", BridgeMain.savesDir.absolutePath)
        putJsonArray("saves") {
            val files = BridgeMain.savesDir.listFiles()?.filter { it.isFile } ?: emptyList()
            for (file in files.sortedByDescending { it.lastModified() }) {
                addJsonObject {
                    put("name", file.name)
                    put("modified", file.lastModified())
                    put("autosave", file.name.startsWith(AUTOSAVE))
                }
            }
        }
    }

    /** params: name (from listSaves) or path - any Unciv save file (e.g. from the desktop game's SaveFiles folder) */
    private fun loadGame(params: JsonObject): JsonElement {
        val file = params.str("name")?.let { File(BridgeMain.savesDir, File(it).name) } ?: File(params.requireStr("path"))
        if (!file.isFile) throw BridgeException("No such save: ${file.absolutePath}")
        val game = UncivFiles.gameInfoFromString(file.readText())
        setGame(game)
        autosaveEnabled = params.bool("remember") != false   // test runs load with remember: false, like newGame
        return StateWriter.summary(game, viewer())
    }

    /** params: name (saved in the bridge's folder, see listSaves) or path. Writes a normal Unciv save the desktop game can open. */
    private fun saveGame(params: JsonObject): JsonElement {
        val file = params.str("name")?.let { File(BridgeMain.savesDir, File(it).name) } ?: File(params.requireStr("path"))
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(UncivFiles.gameInfoToString(game(), forceZip = true))
        return buildJsonObject { put("path", file.absolutePath) }
    }

    // region policies and diplomacy

    /** The policy tree from your point of view. */
    private fun getPolicies(): JsonElement = StateWriter.policies(viewer())

    /** params: policy - a branch name (opens it) or a policy in an open branch. Same rules as Unciv's policy screen. */
    private fun adoptPolicy(params: JsonObject): JsonElement {
        val civ = viewer()
        val name = params.requireStr("policy")
        val policy = civ.gameInfo.ruleset.policies[name] ?: throw BridgeException("Unknown policy '$name'")
        val pickable = civ.isCurrentPlayer() && !civ.isDefeated() && !civ.policies.isAdopted(name)
            && policy.policyBranchType != PolicyBranchType.BranchComplete
            && civ.policies.isAdoptable(policy) && civ.policies.canAdoptPolicy()
        if (!pickable) throw BridgeException(
            if (!civ.policies.canAdoptPolicy()) "Not enough culture for a new policy yet" else "$name can't be adopted right now")
        civ.policies.adopt(policy)
        return StateWriter.policies(civ)
    }

    /** Every civ you've met: status, mood, influence, score, and whether you can declare war or offer peace. */
    private fun getDiplomacy(): JsonElement = StateWriter.diplomacy(viewer())

    private fun findKnownCiv(params: JsonObject): Civilization {
        val name = params.requireStr("civ")
        return viewer().getKnownCivs().firstOrNull { it.civName == name } ?: throw BridgeException("You haven't met $name")
    }

    /** params: civ. Declares war on a civ you've met (when no peace treaty is still running). */
    private fun declareWar(params: JsonObject): JsonElement {
        val other = findKnownCiv(params)
        val diplomacy = viewer().getDiplomacyManager(other)!!
        if (viewer().isAtWarWith(other)) throw BridgeException("You're already at war with ${other.civName}")
        if (diplomacy.turnsToPeaceTreaty() > 0) throw BridgeException("Your peace treaty with ${other.civName} runs ${diplomacy.turnsToPeaceTreaty()} more turns")
        if (!diplomacy.canDeclareWar()) throw BridgeException("You can't declare war on ${other.civName}")
        diplomacy.declareWar()
        return StateWriter.diplomacy(viewer())
    }

    /**
     * params: civ. Proposes peace. City-states accept (as in Unciv's diplomacy screen); a major civ
     * weighs it the way its AI weighs any trade. Result: accepted, message, and the updated diplomacy.
     */
    private fun offerPeace(params: JsonObject): JsonElement {
        val civ = viewer()
        val other = findKnownCiv(params)
        if (!civ.isAtWarWith(other)) throw BridgeException("You're not at war with ${other.civName}")
        if (other.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.DeclaredWar) == true)
            throw BridgeException("${other.civName} won't talk peace so soon after the war was declared")
        if (civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclinedPeace) == true)
            throw BridgeException("${other.civName} turned down peace recently - try again in a few turns")
        // From the other side's point of view: both sides offer a peace treaty
        val logic = TradeLogic(other, civ)
        logic.currentTrade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
        logic.currentTrade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, speed = civ.gameInfo.speed))
        val accepted = other.isCityState || TradeEvaluation().isTradeAcceptable(logic.currentTrade, other, civ)
        if (accepted) logic.acceptTrade()
        else civ.getDiplomacyManager(other)?.setFlag(DiplomacyFlags.DeclinedPeace, 3)
        return buildJsonObject {
            put("accepted", accepted)
            put("message", Lang.t(if (accepted) "[${other.civName}] agrees to peace." else "[${other.civName}] refuses peace for now."))
            put("diplomacy", StateWriter.diplomacy(civ))
        }
    }

    // endregion

    // region trade

    /** A major civ you've met that you can trade with. */
    private fun findTradePartner(params: JsonObject): Civilization {
        val other = findKnownCiv(params)
        if (other.isCityState) throw BridgeException("City-states don't trade like this")
        if (other.isDefeated()) throw BridgeException("${other.civName} is gone")
        return other
    }

    /** params: civ. What each side could put on the table. */
    private fun getTradeOptions(params: JsonObject): JsonElement {
        val civ = viewer()
        val other = findTradePartner(params)
        val logic = TradeLogic(civ, other)
        return buildJsonObject {
            put("civ", other.civName)
            put("yourGold", civ.gold)
            put("theirGold", other.gold)
            putJsonArray("ours") { for (offer in logic.ourAvailableOffers) addJsonObject { StateWriter.writeOffer(this, offer, civ.gameInfo) } }
            putJsonArray("theirs") { for (offer in logic.theirAvailableOffers) addJsonObject { StateWriter.writeOffer(this, offer, civ.gameInfo) } }
        }
    }

    /**
     * Builds the trade described by params ours[] / theirs[] ({name, type, amount}, picked from getTradeOptions).
     * Amounts are clamped to what's available.
     */
    private fun buildTrade(params: JsonObject, civ: Civilization, other: Civilization): TradeLogic {
        val logic = TradeLogic(civ, other)
        fun pick(key: String, available: List<TradeOffer>, into: MutableList<TradeOffer>) {
            val wanted = params[key] as? JsonArray ?: return
            for (element in wanted) {
                val item = element as? JsonObject ?: continue
                val name = item.requireStr("name")
                val type = item.requireStr("type")
                val offer = available.firstOrNull { it.name == name && it.type.name == type }
                    ?: throw BridgeException("$name isn't on the table")
                val amount = (item.int("amount") ?: offer.amount).coerceIn(1, maxOf(1, offer.amount))
                into.add(offer.copy(amount = amount))
            }
        }
        pick("ours", logic.ourAvailableOffers, logic.currentTrade.ourOffers)
        pick("theirs", logic.theirAvailableOffers, logic.currentTrade.theirOffers)
        return logic
    }

    /** params: civ, ours[], theirs[]. Whether they'd take it, judged the way their AI judges trades. */
    private fun evaluateTrade(params: JsonObject): JsonElement {
        val civ = viewer()
        val other = findTradePartner(params)
        val logic = buildTrade(params, civ, other)
        val fromTheirSide = logic.currentTrade.reverse()
        val value = TradeEvaluation().getTradeAcceptability(fromTheirSide, other, civ, true)
        return buildJsonObject {
            val anything = logic.currentTrade.ourOffers.isNotEmpty() || logic.currentTrade.theirOffers.isNotEmpty()
            put("acceptable", anything && value >= 0)
            put("value", if (value == Int.MIN_VALUE) -999999 else value)
        }
    }

    /** params: civ, ours[], theirs[]. They decide right away (Unciv would let them decide on their turn). */
    private fun proposeTrade(params: JsonObject): JsonElement {
        val civ = viewer()
        val other = findTradePartner(params)
        val logic = buildTrade(params, civ, other)
        if (logic.currentTrade.ourOffers.isEmpty() && logic.currentTrade.theirOffers.isEmpty())
            throw BridgeException("Put something on the table first")
        requireTurn()
        return DiplomacyActions.propose(civ, other, logic)
    }

    // endregion

    /** Writes the "Autosave" right away (the bridge is about to exit), so a turn in progress isn't lost. */
    fun saveBeforeExit() {
        val game = gameInfo ?: return
        if (!autosaveEnabled) return
        try {
            BridgeMain.savesDir.mkdirs()
            File(BridgeMain.savesDir, AUTOSAVE).writeText(UncivFiles.gameInfoToString(game, forceZip = true))
            println("Saved the game before exiting")
        } catch (ex: Exception) {
            System.err.println("Saving before exit failed: $ex")
        }
    }

    /** Writes the "Autosave" in the background, from a copy so the game can carry on meanwhile. */
    private fun autosave() {
        if (!autosaveEnabled) return
        val copy = try { game().clone() } catch (ex: Exception) { System.err.println("Autosave skipped: $ex"); return }
        thread(name = "autosave", isDaemon = true) {
            try {
                BridgeMain.savesDir.mkdirs()
                File(BridgeMain.savesDir, AUTOSAVE).writeText(UncivFiles.gameInfoToString(copy, forceZip = true))
            } catch (ex: Exception) {
                System.err.println("Autosave failed: $ex")
            }
        }
    }

    /** params: unitId. Full details for one of your units, including where it can move this turn and what it can do. */
    private fun getUnit(params: JsonObject): JsonElement {
        val unit = findOwnUnit(params)
        val gameView = GameView(game(), viewer())
        val unitView = gameView.getMapUnitView(unit)
        val improvements = buildableImprovements(unit).map {
            it to if (it.name == Constants.cancelImprovementOrder) 0 else it.getTurnsToBuild(unit.civ, unit)
        }
        val promotionChoices = if (canPromoteNow(unit)) unit.promotions.getAvailablePromotions().toList() else emptyList()
        val details = StateWriter.unitDetails(unitView, UnitOrders.list(unit), attackTargets(unitView), improvements,
            canPromoteNow(unit), promotionChoices, specialTargets(unit, unitView))
        val spots = suggestedCitySpots(unit)
        if (spots.isEmpty()) return details
        return JsonObject(details.jsonObject + ("suggestedCitySpots" to buildJsonArray {
            for ((tile, value) in spots) addJsonObject {
                put("x", tile.position.x)
                put("y", tile.position.y)
                put("value", value.toInt())
            }
        }))
    }

    /**
     * v23: Unciv's suggested city locations for a unit that can found a city (WorldMapTileUpdater, "Show suggested
     * city locations for units that can found cities"): the three best explored tiles within 5, worth at least 50.
     */
    private fun suggestedCitySpots(unit: MapUnit): List<Pair<com.unciv.logic.map.tile.Tile, Float>> {
        if (!unit.hasUnique(UniqueType.FoundCity)) return emptyList()
        return try {
            com.unciv.logic.automation.unit.CityLocationTileRanker.getBestTilesToFoundCity(unit, 5, minimumValue = 50f)
                .tileRankMap.entries.filter { it.key.isExplored(unit.civ) }
                .sortedByDescending { it.value }.take(3).map { it.key to it.value }
        } catch (ex: Exception) {
            System.err.println("Couldn't rank city locations: $ex")
            emptyList()
        }
    }

    /** Tiles a nuclear weapon could strike, or an air unit preparing an air sweep could sweep. */
    private fun specialTargets(unit: MapUnit, unitView: MapUnitView): List<Pair<com.unciv.logic.map.tile.Tile, String>> {
        if (!unitView.canAttack()) return emptyList()
        return try {
            when {
                unit.isNuclearWeapon() -> unitView.getTilesInAttackRange().filter { unitView.mayUseNuke(it) }
                    .map { game().tileMap[it.position()] to "nuke" }
                unit.isPreparingAirSweep() -> unitView.getTilesInAttackRange().map { game().tileMap[it.position()] to "airSweep" }
                else -> emptyList()
            }
        } catch (ex: Exception) {
            System.err.println("Couldn't compute nuke / air sweep targets: $ex")
            emptyList()
        }
    }

    /** Everything [unitView] could attack this turn (moving first if needed), as Unciv's own UI computes it. */
    private fun attackTargets(unitView: MapUnitView): List<AttackableTileView> {
        if (!unitView.canAttack()) return emptyList()
        if (unitView.isNuclearWeapon() || unitView.isPreparingAirSweep()) return emptyList()   // see specialTargets
        return try {
            unitView.getAttackableEnemies(unitView.getDistanceToTiles())
        } catch (ex: Exception) {
            System.err.println("Couldn't compute attack targets: $ex")
            emptyList()
        }
    }

    /** Improvements [unit] can start on its current tile right now - the same filter as Unciv's improvement picker. */
    private fun buildableImprovements(unit: MapUnit): List<TileImprovement> {
        val tile = unit.getTile()
        if (!unit.hasMovement() || tile.isCityCenter()) return emptyList()
        return unit.civ.gameInfo.ruleset.tileImprovements.values.filter {
            (it.turnsToBuild != -1 || it.name == Constants.cancelImprovementOrder)
                && it.name != tile.improvement
                && unit.canBuildImprovement(it)
                && tile.improvementFunctions.canBuildImprovement(it, unit.cache.state)
        }
    }

    /**
     * params: unitId, x, y - attack whatever is on that tile (a unit or a city).
     * Melee units move next to the target first if needed, just like right-click-attack in Unciv.
     */
    private fun attack(params: JsonObject): JsonElement {
        val game = game()
        val unit = findOwnUnit(params)
        val target = HexCoord(params.requireInt("x"), params.requireInt("y"))
        val gameView = GameView(game, viewer())
        val unitView = gameView.getMapUnitView(unit)
        if (!unitView.canAttack()) throw BridgeException("${Gameplay.plain(unit.displayName())} can't attack right now")
        if (target !in game.tileMap) throw BridgeException("($target) is off the map")
        val special = specialTargets(unit, unitView).firstOrNull { it.first.position == target }
        if (special != null) {
            val targetView = gameView.getTile(special.first)
            if (special.second == "nuke") unitView.tryNuke(targetView) else unitView.tryAirSweep(targetView)
            val alive = unit.civ.units.getCivUnits().any { it === unit }
            return buildJsonObject {
                put("attacked", true)
                put("defender", if (special.second == "nuke") "Nuclear strike" else "Air sweep")
                put("damageToDefender", 0)
                put("damageToAttacker", 0)
                put("attackerAlive", alive)
                put("attackerHealth", if (alive) unit.health else 0)
                put("defenderDefeated", false)
                put("defenderHealth", 0)
                put("cityCaptured", false)
                put("x", unit.getTile().position.x)
                put("y", unit.getTile().position.y)
            }
        }
        val attackable = attackTargets(unitView).firstOrNull { it.getTileToAttack().position() == target }
            ?: throw BridgeException("${Gameplay.plain(unit.displayName())} can't attack that tile")
        val defender = attackable.getCombatant()
        val defenderName = defender?.getCombatantName() ?: "target"

        if (!unitView.tryMovePreparingAttack(attackable))
            return buildJsonObject {
                put("attacked", false)
                put("message", Lang.t("[${unit.displayName()}] moved into position but can't attack any more this turn"))
            }
        val damage = unitView.attackOrNuke(attackable)
        val attackerAlive = unit.civ.units.getCivUnits().any { it === unit }
        val targetTile = game.tileMap[target]
        // Took a city: Unciv asks what to do with it (its battle code has already made the AI's choice - see Alerts)
        if (targetTile.isCityCenter() && targetTile.getOwner() == unit.civ) Alerts.cityConquered(unit.civ, targetTile.getCity()!!)
        return buildJsonObject {
            put("attacked", true)
            put("defender", defenderName)
            put("damageToDefender", damage.attackerDealt)
            put("damageToAttacker", damage.defenderDealt)
            put("attackerAlive", attackerAlive)
            put("attackerHealth", if (attackerAlive) unit.health else 0)
            put("defenderDefeated", defender?.isDefeated() ?: false)
            put("defenderHealth", defender?.getHealth() ?: 0)
            put("cityCaptured", targetTile.isCityCenter() && targetTile.getOwner() == unit.civ)
            put("x", unit.getTile().position.x)
            put("y", unit.getTile().position.y)
        }
    }

    /** params: unitId, improvement - a name from getUnit().improvements (Unciv's improvement picker, without the screen). */
    private fun buildImprovement(params: JsonObject): JsonElement {
        val unit = findOwnUnit(params)
        val name = params.requireStr("improvement")
        val improvement = buildableImprovements(unit).firstOrNull { it.name == name }
            ?: throw BridgeException("${Gameplay.plain(unit.displayName())} can't build $name here")
        val tile = unit.getTile()
        if (improvement.name == Constants.cancelImprovementOrder) {
            tile.stopWorkingOnImprovement()
        } else {
            if (improvement.name != tile.improvementInProgress)
                tile.startWorkingOnImprovement(improvement, unit.civ, unit)
            unit.action = null // wakes the worker up if it was sleeping
        }
        return buildJsonObject {
            put("improvementInProgress", tile.improvementInProgress)
            put("turnsLeft", tile.turnsToImprovement)
        }
    }

    /**
     * params: unitId, x, y. Moves as far as possible this turn; if the target is further away,
     * the unit keeps heading there on later turns (like right-click-move in Unciv).
     */
    private fun moveUnit(params: JsonObject): JsonElement {
        val game = game()
        val unit = findOwnUnit(params)
        val target = HexCoord(params.requireInt("x"), params.requireInt("y"))
        if (target !in game.tileMap) throw BridgeException("($target) is off the map")

        val gameView = GameView(game, viewer())
        val unitView = gameView.getMapUnitView(unit)
        val targetTileView = gameView.getTile(game.tileMap[target])
        val tileToMoveTo = try {
            unitView.getTileToMoveToThisTurn(targetTileView)
        } catch (_: UnitMovement.UnreachableDestinationException) {
            throw BridgeException("${Gameplay.plain(unit.displayName())} can't reach that tile")
        }

        val previousPosition = unit.getTile().position
        unitView.tryMoveToTile(tileToMoveTo)
        val moved = unit.getTile().position != previousPosition
        if (moved) Tutorials.done(if (unit.baseUnit.isAirUnit()) "Move an air unit" else "Move unit")
        if (moved && (unitView.isExploring() || unitView.isMoving())) unitView.tryResetAction()
        // Not there yet (a far target, or no moves left this turn): keep heading there on later turns
        val queued = unit.getTile().position != target
        if (queued) {
            if (!moved && unitView.isExploring()) unitView.tryResetAction()
            unitView.trySetMoveToAction(targetTileView)
        }
        return buildJsonObject {
            put("moved", moved)
            put("queued", queued)
            put("x", unit.getTile().position.x)
            put("y", unit.getTile().position.y)
            put("movementLeft", unit.currentMovement)
        }
    }

    /**
     * params: unitId, x, y. Where a move there would take the unit, without moving it - like the path and
     * turn count Unciv shows before you press "Move here". stops[i] is where the unit ends turn i + 1.
     * canStay is whether it could actually end up on the tile: a path can lead to a tile it may only pass
     * through, such as one that already holds as many units as Unciv allows.
     */
    private fun previewMove(params: JsonObject): JsonElement {
        val game = game()
        val unit = findOwnUnit(params)
        val target = HexCoord(params.requireInt("x"), params.requireInt("y"))
        if (target !in game.tileMap) throw BridgeException("($target) is off the map")
        val gameView = GameView(game, viewer())
        val unitView = gameView.getMapUnitView(unit)
        val targetTileView = gameView.getTile(game.tileMap[target])
        val path = if (unit.getTile().position == target) emptyList() else try {
            unitView.getShortestPath(targetTileView)
        } catch (ex: Exception) {
            System.err.println("Couldn't find a path: $ex")
            emptyList()
        }
        return buildJsonObject {
            put("canReach", path.isNotEmpty())
            put("canStay", unitView.canMoveTo(targetTileView))
            put("turns", path.size)
            putJsonArray("stops") {
                for (tile in path) addJsonObject {
                    put("x", tile.position().x)
                    put("y", tile.position().y)
                }
            }
        }
    }

    /**
     * params: unitId, action (a UnitActionType name such as "FoundCity", "Fortify", "Sleep", "Explore", "Skip"),
     * optional title to pick between several actions of the same type (e.g. which improvement to build).
     */
    private fun unitAction(params: JsonObject): JsonElement {
        requireTurn()
        val unit = findOwnUnit(params)
        val typeName = params.requireStr("action")
        if (UiUnitActionType.entries.none { it.name == typeName }) throw BridgeException("Unknown unit action '$typeName'")
        val x = params.int("x")
        val y = params.int("y")
        val target = if (x != null && y != null) HexCoord(x, y) else null
        val result = UnitOrders.run(unit, typeName, params.str("title"), target, params.bool("confirm") ?: false)
        if (typeName == UnitActionType.FoundCity.name && unit.isDestroyed) Tutorials.done("Found city")
        return result
    }

    /** params: name (or x, y of the city); tileX, tileY - a tile from getCity().bombardTargets. The city shoots at it. */
    private fun cityAttack(params: JsonObject): JsonElement {
        requireTurn()
        val city = findOwnCity(params)
        val cityView = GameView(game(), viewer()).getCityView(city)
        if (!cityView.canBombard()) throw BridgeException("${city.name} can't attack this turn")
        val position = HexCoord(params.requireInt("tileX"), params.requireInt("tileY"))
        val tile = cityView.getBombardableTiles().firstOrNull { it.position() == position }?.let { game().tileMap[position] }
            ?: throw BridgeException("${city.name} can't hit that tile")
        val defender = com.unciv.logic.battle.Battle.getMapCombatantOfTile(tile) ?: throw BridgeException("There's nothing to hit there")
        val damage = com.unciv.logic.battle.Battle.attackOrNuke(com.unciv.logic.battle.CityCombatant(city),
            com.unciv.logic.battle.AttackableTile(city.getCenterTile(), tile, 0f, defender))
        return buildJsonObject {
            put("attacked", true)
            put("defender", defender.getName())
            put("damageToDefender", damage.attackerDealt)
            put("defenderDefeated", defender.isDefeated())
            put("defenderHealth", defender.getHealth())
            put("city", StateWriter.cityDetails(cityView))
        }
    }

    /**
     * params (all optional): automatedUnitsMoveOnTurnStart - true: your automated / exploring units move at
     * the start of your turn (Unciv's own option); false (default): they move when you end your turn.
     */
    private fun setSettings(params: JsonObject): JsonElement {
        val settings = UncivGame.Current.settings
        // Unciv's own options that its game logic reads (Options > Gameplay / Automation)
        val flags: Map<String, kotlin.reflect.KMutableProperty0<Boolean>> = mapOf(
            "automatedUnitsMoveOnTurnStart" to settings::automatedUnitsMoveOnTurnStart,
            "autoUnitCycle" to settings::autoUnitCycle,
            "alternateUnitCycleOrder" to settings::alternateUnitCycleOrder,
            "autoAssignCityProduction" to settings::autoAssignCityProduction,
            "autoBuildingRoads" to settings::autoBuildingRoads,
            "automatedWorkersReplaceImprovements" to settings::automatedWorkersReplaceImprovements,
            "stopAutomatedWorkersRemoveVegetation" to settings::stopAutomatedWorkersRemoveVegetation,
            "automatedUnitsCanUpgrade" to settings::automatedUnitsCanUpgrade,
            "automatedUnitsChoosePromotions" to settings::automatedUnitsChoosePromotions,
            "citiesAutoBombardAtEndOfTurn" to settings::citiesAutoBombardAtEndOfTurn,
            "autoAssignSpecialistsInNewCities" to settings::autoAssignSpecialistsInNewCities,
        )
        for ((key, property) in flags) params.bool(key)?.let { property.set(it) }
        params.bool("checkForDueUnits")?.let { checkForDueUnits = it }
        // Options > Gameplay: how many turns the notification log keeps (Unciv's slider allows 3-15)
        params.int("notificationsLogMaxTurns")?.let { settings.notificationsLogMaxTurns = it.coerceIn(3, 15) }
        // Options > Advanced: autosave every so many turns
        params.int("turnsBetweenAutosaves")?.let { settings.turnsBetweenAutosaves = it.coerceAtLeast(1) }
        // v22: Options > Display > Show tutorials, and Reset tutorials
        params.bool("showTutorials")?.let { Tutorials.enabled = it }
        // v24: the language (Unciv's name for it, as getLanguages lists them)
        params.str("language")?.let { Lang.set(it) }
        if (params.bool("resetTutorials") == true) Tutorials.reset()
        return buildJsonObject {
            put("showTutorials", Tutorials.enabled)
            put("language", Lang.language)
            for ((key, property) in flags) put(key, property.get())
            put("checkForDueUnits", checkForDueUnits)
            put("notificationsLogMaxTurns", settings.notificationsLogMaxTurns)
            put("turnsBetweenAutosaves", settings.turnsBetweenAutosaves)
        }
    }

    /** Same rule as Unciv's promotion screen: enough XP, and the unit hasn't moved away its turn or attacked. */
    private fun canPromoteNow(unit: MapUnit): Boolean =
        unit.promotions.canBePromoted() && unit.hasMovement() && unit.attacksThisTurn == 0

    /** params: unitId, promotion - a name from getUnit().promotionChoices. */
    private fun promote(params: JsonObject): JsonElement {
        val unit = findOwnUnit(params)
        val name = params.requireStr("promotion")
        if (!canPromoteNow(unit)) throw BridgeException("${Gameplay.plain(unit.displayName())} can't be promoted right now")
        if (unit.promotions.getAvailablePromotions().none { it.name == name })
            throw BridgeException("$name isn't available to ${Gameplay.plain(unit.displayName())}")
        val gameView = GameView(game(), viewer())
        gameView.getMapUnitView(unit).tryAddPromotion(name)
        return getUnit(params)
    }

    /** params: name or x, y. Production, growth, queue and everything the city can build. */
    private fun getCity(params: JsonObject): JsonElement {
        val city = findOwnCity(params)
        val cityView = GameView(game(), viewer()).getCityView(city)
        return StateWriter.cityDetails(cityView)
    }

    /**
     * params: name or x, y; construction; mode "now" (default: build it next, ahead of the queue)
     * or "queue" (add to the end). Same rules as Unciv's city screen.
     */
    private fun setProduction(params: JsonObject): JsonElement {
        val city = findOwnCity(params)
        val cityView = GameView(game(), viewer()).getCityView(city)
        val name = params.requireStr("construction")
        val ruleset = city.getRuleset()
        val construction: IConstruction = ruleset.units[name] ?: ruleset.buildings[name]
            ?: PerpetualConstruction.perpetualConstructionsMap[name]
            ?: throw BridgeException("Unknown construction '$name'")
        val constructions = cityView.constructions
        if (cityView.isPuppet()) throw BridgeException("${city.name} is a puppet - you can't choose its production")
        if (!constructions.isBuildable(construction)) throw BridgeException("${city.name} can't build $name")
        val now = (params.str("mode") ?: "now") == "now"
        // "Build now" on a building that's already in the queue: move it to the front instead of queueing it twice
        val queued = constructions.constructionQueue.indexOf(name)
        if (now && construction is Building && queued >= 0) {
            if (queued > 0) cityView.tryMoveEntryToTop(queued)
            if (constructions.currentConstructionName() == name) cityView.tryReassignPopulation()
            return StateWriter.cityDetails(cityView)
        }
        if (!constructions.canAddToQueue(construction)) throw BridgeException(
            if (constructions.isQueueFull()) "The queue is full - take something out of it first"
            else "$name is already in the queue")
        if (construction is Building && construction.hasCreateOneImprovementUnique()) {
            // Unciv's city screen asks for the tile the improvement goes on, then queues the building with it
            val tileView = placeTile(cityView, construction, params)
            cityView.tryAddToQueueWithTile(construction, tileView)
            if (now) {
                val index = constructions.constructionQueue.lastIndexOf(name)
                if (index > 0) cityView.tryMoveEntryToTop(index)
            }
        } else cityView.tryAddToQueueConstruction(construction, addToTop = now)
        if (constructions.currentConstructionName() == name) cityView.tryReassignPopulation()
        Tutorials.done("Pick construction")
        return StateWriter.cityDetails(cityView)
    }

    /** params: name or x, y; index into the queue (0 = what's being built now). */
    private fun removeFromQueue(params: JsonObject): JsonElement {
        val city = findOwnCity(params)
        val cityView = GameView(game(), viewer()).getCityView(city)
        val index = params.requireInt("index")
        if (index !in cityView.constructions.constructionQueue.indices) throw BridgeException("No queue entry $index")
        cityView.tryRemoveFromQueue(index, false)
        return StateWriter.cityDetails(cityView)
    }

    /**
     * params: name or x, y; construction; stat (optional: gold - the default - or faith, food, science, culture,
     * where the rules allow it); queueIndex (optional: buying a queue entry finishes that entry). Buys it right now.
     */
    private fun buyConstruction(params: JsonObject): JsonElement {
        val city = findOwnCity(params)
        val cityView = GameView(game(), viewer()).getCityView(city)
        val name = params.requireStr("construction")
        val ruleset = city.getRuleset()
        val construction: INonPerpetualConstruction = ruleset.units[name] ?: ruleset.buildings[name]
            ?: throw BridgeException("Can't buy '$name'")
        val statName = params.str("stat") ?: "gold"
        val stat = Stat.statsUsableToBuy.firstOrNull { it.name.equals(statName, ignoreCase = true) }
            ?: throw BridgeException("Can't buy with '$statName'")
        val constructions = cityView.constructions
        // A building that creates an improvement: on the tile chosen now, or the one it was queued with
        val placeTile = if (construction is Building && construction.hasCreateOneImprovementUnique()) {
            if (params.int("tileX") != null) placeTile(cityView, construction, params)
            else cityView.getImprovementToCreate(construction)?.let { constructions.getTileForImprovement(it.name) }
                ?: throw BridgeException("Pick the tile the ${cityView.getImprovementToCreate(construction)?.name ?: "improvement"} goes on")
        } else null
        if (!cityView.canBePurchasedWithStat(construction, stat)) throw BridgeException("$name can't be bought with ${stat.name.lowercase()}")
        val cost = constructions.getStatBuyCost(construction, stat)
            ?: throw BridgeException("$name can't be bought with ${stat.name.lowercase()}")
        if (constructions.isConstructionPurchaseBlockedByUnit(construction))
            throw BridgeException("Move the unit out of ${city.name} first")
        if (!constructions.isConstructionPurchaseAllowed(construction, stat, cost))
            throw BridgeException("Can't buy $name for $cost ${stat.name.lowercase()} (not enough, or not allowed here)")
        val queueIndex = params.int("queueIndex") ?: -1
        if (!constructions.purchaseConstruction(construction, queueIndex, stat, placeTile))
            throw BridgeException("Buying $name failed (no room to place it?)")
        return StateWriter.cityDetails(cityView)
    }

    /** params tileX, tileY: where [building]'s improvement goes - checked like Unciv's city screen does. */
    private fun placeTile(cityView: com.unciv.view.CityView, building: Building, params: JsonObject): com.unciv.view.TileView {
        val improvement = cityView.getImprovementToCreate(building) ?: throw BridgeException("${building.name} has nothing to place")
        val x = params.int("tileX") ?: throw BridgeException("Pick the tile the ${improvement.name} goes on (tileX, tileY)")
        val y = params.requireInt("tileY")
        val position = HexCoord(x, y)
        if (position !in game().tileMap) throw BridgeException("($position) is off the map")
        val tileView = GameView(game(), viewer()).getTile(game().tileMap[position])
        if (!cityView.isOwnedTile(tileView) || !cityView.constructions.canPlaceCreateOneImprovementOn(improvement, tileView))
            throw BridgeException("The ${improvement.name} can't go there")
        return tileView
    }

    /** Runs one of [CityView]'s try... actions on your city (params: name or x, y) and returns the updated city. */
    private fun cityChange(params: JsonObject, allowPuppet: Boolean = false, change: (com.unciv.view.CityView) -> Boolean): JsonElement {
        val city = findOwnCity(params)
        val cityView = GameView(game(), viewer()).getCityView(city)
        if (!viewer().isCurrentPlayer()) throw BridgeException("It isn't your turn")
        // Puppets run themselves; as in Unciv's city screen you can only annex (or raze) them
        if (city.isPuppet && !allowPuppet) throw BridgeException("${city.name} is a puppet - annex it to manage it")
        if (!change(cityView)) throw BridgeException("${city.name}: that isn't possible right now")
        cityView.updateCityStats()
        return StateWriter.cityDetails(cityView)
    }

    /**
     * params: city (name or x, y); tileX, tileY; action: "toggle" (Unciv's click: work an unworked tile when a
     * citizen is free, otherwise stop working it), "work", "unwork", "lock" (also works it), "unlock", "buy".
     */
    private fun cityTile(params: JsonObject): JsonElement {
        val city = findOwnCity(params)
        val gameView = GameView(game(), viewer())
        val cityView = gameView.getCityView(city)
        if (!viewer().isCurrentPlayer()) throw BridgeException("It isn't your turn")
        val position = HexCoord(params.requireInt("tileX"), params.requireInt("tileY"))
        if (position !in game().tileMap) throw BridgeException("($position) is off the map")
        val tile = game().tileMap[position]
        val tileView = gameView.getTile(tile)
        val action = params.str("action") ?: "toggle"
        if (action == "buy") {
            if (!cityView.canBuyTile(tileView)) throw BridgeException("${city.name} can't buy that tile")
            val cost = cityView.getGoldCostOfTile(tileView)
            if (!viewer().hasStatToBuy(Stat.Gold, cost)) throw BridgeException("That tile costs $cost gold - you have ${viewer().gold}")
            if (!cityView.tryBuyTile(tileView)) throw BridgeException("Buying the tile failed")
            cityView.updateCityStats()
            return StateWriter.cityDetails(cityView)
        }
        if (city.isPuppet) throw BridgeException("${city.name} is a puppet - it works its own tiles")
        val workable = tile in city.getWorkableTiles() && !tile.isCityCenter() && tile.getWorkingCity().let { it == null || it == city }
        if (!workable) throw BridgeException("${city.name} can't work that tile")
        val worked = city.isWorked(tile)
        fun work() {
            if (city.population.getFreePopulation() <= 0)
                throw BridgeException("No free citizens - take one off another tile or a specialist first")
            cityView.tryWorkTile(tileView)
        }
        when (action) {
            "toggle" -> if (!worked && city.population.getFreePopulation() > 0) work() else if (worked) cityView.tryStopWorkingTile(tileView)
                else throw BridgeException("No free citizens - take one off another tile or a specialist first")
            "work" -> if (!worked) work()
            "unwork" -> cityView.tryStopWorkingTile(tileView)
            "lock" -> { if (!worked) work(); cityView.tryLockTile(tileView) }
            "unlock" -> cityView.tryUnlockTile(tileView)
            else -> throw BridgeException("Unknown tile action '$action'")
        }
        Tutorials.done("Reassign worked tiles")
        cityView.updateCityStats()
        return StateWriter.cityDetails(cityView)
    }

    /**
     * params: tech. Researches it, along with any missing prerequisites first, like clicking a tech in
     * Unciv's tech tree. When you have a free tech to pick (e.g. from a wonder), it's granted instead.
     */
    private fun setResearch(params: JsonObject): JsonElement {
        val civ = viewer()
        val name = params.requireStr("tech")
        val tech = civ.gameInfo.ruleset.technologies[name] ?: throw BridgeException("Unknown tech '$name'")
        val techs = civ.tech
        if (techs.freeTechs > 0 && techs.canBeResearched(name)) {
            techs.getFreeTechnology(name)
        } else {
            val path = techs.getRequiredTechsToDestination(tech)
            if (path.isEmpty()) throw BridgeException("$name is already researched or can't be researched")
            techs.techsToResearch = ArrayList(path.map { it.name })
        }
        techs.updateResearchProgress()
        Tutorials.done("Pick technology")
        return StateWriter.techs(civ)
    }

    /** Lists every action Unciv offers for [unit], stopping quietly at the first one that needs the Unciv UI. */
    private fun safeUnitActions(unit: MapUnit): List<UnitAction> {
        val result = ArrayList<UnitAction>()
        try {
            for (action in UnitActions.getUnitActions(unit)) result.add(action)
        } catch (_: Exception) {
            // Escort/swap/disband factories look at Unciv's world screen; we simply don't offer those yet.
        }
        return result
    }

    private fun endTurn(): JsonElement {
        val game = game()
        val player = viewer()
        if (player.isCurrentPlayer() && !player.isDefeated()) {
            val blockers = Gameplay.blockers(game, player, Alerts.countNeedingAnswer(game, player), checkForDueUnits)
            if (blockers.isNotEmpty()) throw BridgeException("Not yet: ${blockers.first()["text"].toString().trim('"')}")
            Alerts.dismissInformational(game, player)
        }
        // Unciv's "Move automated units" step: explorers, automated workers and units on multi-turn
        // moves act now, as your turn ends, before the AI players move.
        if (!player.hasMovedAutomatedUnits) {
            val civView = GameView(game, player).civView
            civView.tryMarkMovedAutomatedUnits()
            civView.tryAutomateAllUnits()
        }
        val before = TurnEvents.snapshot(game, player)
        game.nextTurn()
        Tutorials.done("Pass a turn")
        if (game.turns % UncivGame.Current.settings.turnsBetweenAutosaves.coerceAtLeast(1) == 0) autosave()   // as Unciv
        val next = viewer()
        val events = try {
            TurnEvents.events(game, next, before)
        } catch (ex: Exception) {
            System.err.println("Couldn't work out the turn's events: $ex")
            JsonArray(emptyList())
        }
        return buildJsonObject {
            put("events", events)
            put("turn", game.turns)
            put("currentPlayer", game.currentPlayer)
            put("yourTurn", next == game.getCurrentPlayerCivilization())
            put("gameOver", game.victoryData != null)
            game.victoryData?.let {
                put("winner", it.winningCiv)
                put("victoryType", it.victoryType)
            }
            put("defeated", player.isDefeated())
            putJsonArray("notifications") {
                next.notifications.forEach {
                    addJsonObject {
                        put("text", Lang.t(it.text))
                        put("category", it.category.name)
                    }
                }
            }
        }
    }
}
