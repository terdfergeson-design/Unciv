package com.unciv.bridge

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.view.AttackableTileView
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Holds the one game the bridge is running and implements every protocol method.
 * All calls arrive already serialized (one at a time) from [BridgeServer].
 *
 * Reads go through Unciv's View API ([GameView] etc.), so the client only learns what
 * the human player is allowed to see. See bridge/PROTOCOL.md for the method list.
 */
class GameSession {
    private var gameInfo: GameInfo? = null

    fun handle(method: String, params: JsonObject): JsonElement = when (method) {
        "ping" -> ping()
        "listOptions" -> listOptions(params)
        "newGame" -> newGame(params)
        "loadGame" -> loadGame(params)
        "saveGame" -> saveGame(params)
        "getState" -> StateWriter.state(game(), viewer())
        "getRuleset" -> StateWriter.ruleset(game())
        "getUnit" -> getUnit(params)
        "moveUnit" -> moveUnit(params)
        "unitAction" -> unitAction(params)
        "attack" -> attack(params)
        "buildImprovement" -> buildImprovement(params)
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
        UncivGame.Current.gameInfo = game
    }

    private fun findOwnUnit(params: JsonObject): MapUnit {
        val id = params.requireInt("unitId")
        return viewer().units.getCivUnits().firstOrNull { it.id == id }
            ?: throw BridgeException("You have no unit with id $id")
    }

    // endregion

    private fun ping(): JsonElement = buildJsonObject {
        put("protocol", BridgeMain.PROTOCOL_VERSION)
        put("uncivVersion", UncivGame.VERSION.text)
        put("gameRunning", gameInfo != null)
    }

    /** What newGame accepts: rulesets, nations, map sizes, difficulties. */
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
                    }
                }
            }
            putJsonArray("difficulties") { ruleset.difficulties.keys.forEach { add(it) } }
            putJsonArray("mapSizes") { MapSize.Predefined.entries.forEach { add(it.name) } }
            putJsonArray("mapShapes") { MapShape.allValues.forEach { add(it) } }
        }
    }

    /**
     * params (all optional): civ, ruleset, mapSize ("Tiny".."Huge"), mapShape, mapType,
     * aiPlayers (default 3), cityStates (default 4), difficulty (default "Prince"), seed, noBarbarians
     */
    private fun newGame(params: JsonObject): JsonElement {
        val rulesetName = params.str("ruleset") ?: BaseRuleset.Civ_V_GnK.fullName
        val ruleset = RulesetCache[rulesetName] ?: throw BridgeException("Unknown ruleset '$rulesetName'")
        val majorNations = ruleset.nations.values.filter { it.isMajorCiv }.map { it.name }
        val civ = params.str("civ") ?: majorNations.random()
        if (civ !in majorNations) throw BridgeException("Unknown nation '$civ'")
        val difficulty = params.str("difficulty") ?: "Prince"
        if (difficulty !in ruleset.difficulties) throw BridgeException("Unknown difficulty '$difficulty'")
        val aiPlayers = (params.int("aiPlayers") ?: 3).coerceIn(1, majorNations.size - 1)

        val setup = GameSetupInfo()
        setup.gameParameters.apply {
            baseRuleset = rulesetName
            this.difficulty = difficulty
            players = ArrayList<Player>().apply {
                add(Player(civ, PlayerType.Human))
                repeat(aiPlayers) { add(Player()) } // random AI nations
            }
            numberOfCityStates = params.int("cityStates") ?: 4
            minNumberOfCityStates = numberOfCityStates
            maxNumberOfCityStates = numberOfCityStates
            noBarbarians = params.bool("noBarbarians") ?: false
        }
        setup.mapParameters.apply {
            mapSize = when (params.str("mapSize") ?: "Tiny") {
                "Tiny" -> MapSize.Tiny
                "Small" -> MapSize.Small
                "Medium" -> MapSize.Medium
                "Large" -> MapSize.Large
                "Huge" -> MapSize.Huge
                else -> throw BridgeException("Unknown map size - use Tiny, Small, Medium, Large or Huge")
            }
            shape = params.str("mapShape") ?: MapShape.hexagonal
            params.str("mapType")?.let { type = it }
            worldWrap = false // keeps phase-0 rendering simple
            params.long("seed")?.let { seed = it }
        }

        val game = GameStarter.startNewGame(setup)
        game.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        setGame(game)
        return StateWriter.summary(game, viewer())
    }

    /** params: path - an Unciv save file (e.g. from the desktop game's SaveFiles folder) */
    private fun loadGame(params: JsonObject): JsonElement {
        val file = File(params.requireStr("path"))
        if (!file.isFile) throw BridgeException("No such file: ${file.absolutePath}")
        val game = UncivFiles.gameInfoFromString(file.readText())
        setGame(game)
        return StateWriter.summary(game, viewer())
    }

    /** params: path. Writes a normal Unciv save that the desktop game can open. */
    private fun saveGame(params: JsonObject): JsonElement {
        val file = File(params.requireStr("path"))
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(UncivFiles.gameInfoToString(game(), forceZip = true))
        return buildJsonObject { put("path", file.absolutePath) }
    }

    /** params: unitId. Full details for one of your units, including where it can move this turn and what it can do. */
    private fun getUnit(params: JsonObject): JsonElement {
        val unit = findOwnUnit(params)
        val gameView = GameView(game(), viewer())
        val unitView = gameView.getMapUnitView(unit)
        val improvements = buildableImprovements(unit).map {
            it to if (it.name == Constants.cancelImprovementOrder) 0 else it.getTurnsToBuild(unit.civ, unit)
        }
        return StateWriter.unitDetails(unitView, safeUnitActions(unit), attackTargets(unitView), improvements)
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

        val previousTile = unitView.getTile()
        unitView.tryMoveToTile(tileToMoveTo)
        val moved = previousTile != unitView.getTile()
        if (moved) {
            if (unitView.isExploring() || unitView.isMoving()) unitView.tryResetAction()
            if (unitView.getTile() != targetTileView) unitView.trySetMoveToAction(targetTileView)
        }
        return buildJsonObject {
            put("moved", moved)
            put("x", unit.getTile().position.x)
            put("y", unit.getTile().position.y)
            put("movementLeft", unit.currentMovement)
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
        game.nextTurn()
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
