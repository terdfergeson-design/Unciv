package com.unciv.bridge

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.city.City
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
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.view.AttackableTileView
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
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
        "getTechs" -> StateWriter.techs(viewer())
        "setResearch" -> setResearch(params)
        "promote" -> promote(params)
        "setSettings" -> setSettings(params)
        "endTurn" -> endTurn()
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
        return JsonObject(state + mapOf(
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
                        put("uniqueText", it.uniqueText.ifEmpty { it.uniques.joinToString("\n") })
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
        put("setup", JsonObject(DEFAULT_SETUP + (saved ?: emptyMap())))
    }

    /**
     * Starts a new game. params, all optional (see getSetup for the defaults and listOptions for allowed values):
     * civ ("Random" or a nation), ruleset, difficulty, speed, startingEra, aiPlayers, cityStates, victoryTypes[],
     * mapSize, mapShape, mapType, mapResources, noRuins, noNaturalWonders, noBarbarians, ragingBarbarians,
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
            worldWrap = false // the table doesn't draw wrapped maps yet
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
        autosaveEnabled = true
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
            put("message", if (accepted) "${other.civName} agrees to peace." else "${other.civName} refuses peace for now.")
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
        val accepted = TradeEvaluation().isTradeAcceptable(logic.currentTrade.reverse(), other, civ)
        if (accepted) logic.acceptTrade()
        return buildJsonObject {
            put("accepted", accepted)
            put("message", if (accepted) "${other.civName} accepts the deal." else "${other.civName} turns the deal down.")
            put("diplomacy", StateWriter.diplomacy(civ))
        }
    }

    // endregion

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
        return StateWriter.unitDetails(unitView, safeUnitActions(unit), attackTargets(unitView), improvements,
            canPromoteNow(unit), promotionChoices)
    }

    /** Everything [unitView] could attack this turn (moving first if needed), as Unciv's own UI computes it. */
    private fun attackTargets(unitView: MapUnitView): List<AttackableTileView> {
        if (!unitView.canAttack()) return emptyList()
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
        if (!unitView.canAttack()) throw BridgeException("${unit.displayName()} can't attack right now")
        val attackable = attackTargets(unitView).firstOrNull { it.getTileToAttack().position() == target }
            ?: throw BridgeException("${unit.displayName()} can't attack that tile")
        val defender = attackable.getCombatant()
        val defenderName = defender?.getCombatantName() ?: "target"

        if (!unitView.tryMovePreparingAttack(attackable))
            return buildJsonObject {
                put("attacked", false)
                put("message", "${unit.displayName()} moved into position but can't attack any more this turn")
            }
        val damage = unitView.attackOrNuke(attackable)
        val attackerAlive = unit.civ.units.getCivUnits().any { it === unit }
        val targetTile = game.tileMap[target]
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
            ?: throw BridgeException("${unit.displayName()} can't build $name here")
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
            throw BridgeException("${unit.displayName()} can't reach that tile")
        }

        val previousPosition = unit.getTile().position
        unitView.tryMoveToTile(tileToMoveTo)
        val moved = unit.getTile().position != previousPosition
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
        val unit = findOwnUnit(params)
        val typeName = params.requireStr("action")
        val type = UnitActionType.entries.firstOrNull { it.name == typeName }
            ?: throw BridgeException("Unknown unit action '$typeName'")
        val title = params.str("title")

        // getUnitActions(unit, type) is lazy, so we stop as soon as we find the action -
        // before reaching the few action factories that need Unciv's own world screen.
        val action = UnitActions.getUnitActions(unit, type)
            .firstOrNull { it.action != null && (title == null || it.title == title) }
            ?: throw BridgeException("${unit.displayName()} can't do $typeName right now")
        try {
            action.action!!.invoke()
        } catch (ex: Exception) {
            if (ex is UninitializedPropertyAccessException || ex is NullPointerException)
                throw BridgeException("$typeName needs one of Unciv's own popups, which the bridge doesn't support yet")
            throw ex
        }
        return buildJsonObject {
            put("done", typeName)
            put("unitStillExists", unit.civ.units.getCivUnits().any { it === unit })
        }
    }

    /**
     * params (all optional): automatedUnitsMoveOnTurnStart - true: your automated / exploring units move at
     * the start of your turn (Unciv's own option); false (default): they move when you end your turn.
     */
    private fun setSettings(params: JsonObject): JsonElement {
        val settings = UncivGame.Current.settings
        params.bool("automatedUnitsMoveOnTurnStart")?.let { settings.automatedUnitsMoveOnTurnStart = it }
        return buildJsonObject {
            put("automatedUnitsMoveOnTurnStart", settings.automatedUnitsMoveOnTurnStart)
        }
    }

    /** Same rule as Unciv's promotion screen: enough XP, and the unit hasn't moved away its turn or attacked. */
    private fun canPromoteNow(unit: MapUnit): Boolean =
        unit.promotions.canBePromoted() && unit.hasMovement() && unit.attacksThisTurn == 0

    /** params: unitId, promotion - a name from getUnit().promotionChoices. */
    private fun promote(params: JsonObject): JsonElement {
        val unit = findOwnUnit(params)
        val name = params.requireStr("promotion")
        if (!canPromoteNow(unit)) throw BridgeException("${unit.displayName()} can't be promoted right now")
        if (unit.promotions.getAvailablePromotions().none { it.name == name })
            throw BridgeException("$name isn't available to ${unit.displayName()}")
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
        if (construction is Building && construction.hasCreateOneImprovementUnique())
            throw BridgeException("$name needs a target tile, which the bridge doesn't support yet")
        if (!constructions.canAddToQueue(construction)) throw BridgeException("$name can't be added (is the queue full?)")
        val now = (params.str("mode") ?: "now") == "now"
        cityView.tryAddToQueueConstruction(construction, addToTop = now)
        if (constructions.currentConstructionName() == name) cityView.tryReassignPopulation()
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

    /** params: name or x, y; construction. Buys it with gold right now. */
    private fun buyConstruction(params: JsonObject): JsonElement {
        val city = findOwnCity(params)
        val cityView = GameView(game(), viewer()).getCityView(city)
        val name = params.requireStr("construction")
        val ruleset = city.getRuleset()
        val construction: INonPerpetualConstruction = ruleset.units[name] ?: ruleset.buildings[name]
            ?: throw BridgeException("Can't buy '$name'")
        val constructions = cityView.constructions
        val cost = constructions.getStatBuyCost(construction, Stat.Gold)
            ?: throw BridgeException("$name can't be bought with gold")
        if (!constructions.isConstructionPurchaseAllowed(construction, Stat.Gold, cost))
            throw BridgeException("Can't buy $name for $cost gold (not enough gold, or not allowed here)")
        if (!constructions.purchaseConstruction(construction, -1, Stat.Gold, null))
            throw BridgeException("Buying $name failed")
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
        // Unciv's "Move automated units" step: explorers, automated workers and units on multi-turn
        // moves act now, as your turn ends, before the AI players move.
        if (!player.hasMovedAutomatedUnits) {
            val civView = GameView(game, player).civView
            civView.tryMarkMovedAutomatedUnits()
            civView.tryAutomateAllUnits()
        }
        game.nextTurn()
        autosave()
        val next = viewer()
        return buildJsonObject {
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
                        put("text", it.text)
                        put("category", it.category.name)
                    }
                }
            }
        }
    }
}
