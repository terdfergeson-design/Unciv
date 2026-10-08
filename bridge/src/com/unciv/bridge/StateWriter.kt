package com.unciv.bridge

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.models.UnitAction
import com.unciv.view.ForeignMapUnitView
import com.unciv.view.GameView
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns game state into JSON for the client, always from the point of view of one civ.
 *
 * Coordinates: every tile has Unciv's hex coordinates (x, y) - use these when sending commands -
 * and world coordinates (wx, wy) for drawing. Hexes have a circumradius of 1 world unit and are
 * flat-topped; +wy points "north" on the map.
 */
object StateWriter {

    /** Small overview returned by newGame/loadGame. */
    fun summary(game: GameInfo, viewer: Civilization): JsonObject = buildJsonObject {
        put("gameId", game.gameId)
        put("ruleset", game.gameParameters.baseRuleset)
        put("turn", game.turns)
        put("playerCiv", viewer.civName)
        put("tileCount", game.tileMap.tileList.size)
        put("mapRadius", game.tileMap.mapParameters.mapSize.radius)
    }

    /** Everything the client needs to draw the world and the player's empire. */
    fun state(game: GameInfo, viewer: Civilization): JsonObject {
        val gameView = GameView(game, viewer)
        val civView = gameView.civView
        return buildJsonObject {
            put("turn", game.turns)
            put("year", game.getYear())
            put("currentPlayer", game.currentPlayer)
            put("playerCiv", viewer.civName)

            putJsonObject("player") {
                put("gold", viewer.gold)
                val stats = viewer.stats.statsForNextTurn
                put("goldPerTurn", stats.gold)
                put("science", stats.science)
                put("culture", stats.culture)
                put("faith", stats.faith)
                put("happiness", civView.getHappiness())
                put("currentTech", civView.currentTechnologyName())
            }

            putJsonArray("civs") {
                for (civ in game.civilizations) {
                    if (civ.isSpectator()) continue
                    val known = civ == viewer || viewer.knows(civ)
                    addJsonObject {
                        put("name", civ.civName)
                        put("known", known)
                        put("outerColor", civ.nation.getOuterColor().toString())
                        put("innerColor", civ.nation.getInnerColor().toString())
                        put("isCityState", civ.isCityState)
                        put("isBarbarian", civ.isBarbarian)
                        put("isHuman", civ.isHuman())
                        put("alive", civ.isAlive())
                    }
                }
            }

            putJsonArray("tiles") {
                for (tile in game.tileMap.tileList) {
                    addJsonObject { writeTile(gameView.getTile(tile), gameView) }
                }
            }

            putJsonArray("units") {
                for (unit in civView.getUnits()) addJsonObject { writeOwnUnit(unit) }
            }

            putJsonArray("cities") {
                for (city in civView.cities()) {
                    addJsonObject {
                        put("name", city.name)
                        put("x", city.location.x)
                        put("y", city.location.y)
                        put("population", city.getPopulationCount())
                        put("isCapital", city.isCapital())
                        put("health", city.getHealth())
                        put("maxHealth", city.getMaxHealth())
                        put("producing", city.currentConstructionName())
                        put("turnsToGrowth", city.getNumTurnsToNewPopulation())
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.writeTile(tile: TileView, gameView: GameView) {
        val position = tile.position()
        val world = HexMath.hex2WorldCoords(position)
        put("x", position.x)
        put("y", position.y)
        put("wx", world.x)
        put("wy", world.y)
        val explored = tile.isExplored()
        put("explored", explored)
        if (!explored) return

        val visible = tile.isVisible()
        put("visible", visible)
        put("base", tile.baseTerrain)
        putJsonArray("features") { tile.terrainFeatures.forEach { add(it) } }
        tile.naturalWonder?.let { put("wonder", it) }
        tile.getViewableResource(gameView.civView)?.let { put("resource", it.name) }
        tile.getShownImprovement()?.let { put("improvement", it) }
        put("road", tile.roadStatus.name)
        if (tile.hasBottomRiver || tile.hasBottomLeftRiver || tile.hasBottomRightRiver) {
            putJsonObject("river") {
                put("bottom", tile.hasBottomRiver)
                put("bottomLeft", tile.hasBottomLeftRiver)
                put("bottomRight", tile.hasBottomRightRiver)
            }
        }
        tile.getOwner()?.let { put("owner", it.civName) }

        if (tile.isCityCenter()) {
            tile.owningCity()?.let { city ->
                putJsonObject("city") {
                    put("name", city.name)
                    put("civ", city.owningCiv().civName)
                    put("population", city.getPopulationCount())
                    put("isCapital", city.isCapital())
                    put("health", city.getHealth())
                    put("maxHealth", city.getMaxHealth())
                }
            }
        }

        if (visible) {
            val units = tile.getVisibleUnits()
            if (units.isNotEmpty()) putJsonArray("units") {
                for (unit in units) addJsonObject { writeForeignUnit(unit) }
            }
        }
    }

    private fun JsonObjectBuilder.writeForeignUnit(unit: ForeignMapUnitView) {
        put("id", unit.id)
        put("name", unit.getBaseUnit().name)
        put("civ", unit.civName)
        put("health", unit.unitHealth)
        put("civilian", unit.isCivilian())
        put("embarked", unit.isEmbarked())
        put("fortified", unit.isFortified())
    }

    private fun JsonObjectBuilder.writeOwnUnit(unit: MapUnitView) {
        writeForeignUnit(unit)
        val position = unit.getTile().position()
        put("x", position.x)
        put("y", position.y)
        put("movement", unit.currentMovement)
        put("maxMovement", unit.getMaxMovement())
        put("idle", unit.isIdle())
        put("sleeping", unit.isSleeping())
        put("moving", unit.isMoving())
        put("automated", unit.isAutomated())
        put("strength", unit.getBaseUnit().strength)
        put("rangedStrength", unit.getBaseUnit().rangedStrength)
        put("range", unit.getRange())
    }

    /** One of the player's units, plus where it can go this turn and which actions it has. */
    fun unitDetails(unit: MapUnitView, actions: List<UnitAction>): JsonObject = buildJsonObject {
        writeOwnUnit(unit)
        putJsonArray("reachable") {
            for (tile in unit.getReachableTilesInCurrentTurn()) {
                val position = tile.position()
                addJsonObject {
                    put("x", position.x)
                    put("y", position.y)
                }
            }
        }
        putJsonArray("actions") {
            for (action in actions) {
                addJsonObject {
                    put("type", action.type.name)
                    put("title", action.title)
                    put("enabled", action.action != null)
                    put("current", action.isCurrentAction)
                }
            }
        }
    }

    /** Static ruleset data the client needs for rendering: terrain colors, resource and unit lists. */
    fun ruleset(game: GameInfo): JsonObject {
        val ruleset = game.ruleset
        return buildJsonObject {
            put("name", ruleset.name)
            putJsonObject("terrains") {
                for (terrain in ruleset.terrains.values) {
                    putJsonObject(terrain.name) {
                        put("type", terrain.type.name)
                        terrain.RGB?.let { rgb -> putJsonArray("rgb") { rgb.forEach { add(it) } } }
                        put("impassable", terrain.impassable)
                    }
                }
            }
            putJsonObject("resources") {
                for (resource in ruleset.tileResources.values) {
                    putJsonObject(resource.name) { put("type", resource.resourceType.name) }
                }
            }
            putJsonObject("units") {
                for (unit in ruleset.units.values) {
                    putJsonObject(unit.name) {
                        put("unitType", unit.unitType)
                        put("strength", unit.strength)
                        put("rangedStrength", unit.rangedStrength)
                        put("movement", unit.movement)
                    }
                }
            }
        }
    }
}
