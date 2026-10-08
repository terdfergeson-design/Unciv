package com.unciv.bridge

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.models.UnitAction
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unit.Promotion
import com.unciv.view.AttackableTileView
import com.unciv.view.CityView
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
                put("canResearch", civView.canResearchTech())
                put("freeTechs", viewer.tech.freeTechs)
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
                        put("producingTurns", safeTurns(city, city.currentConstructionName(), true))
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

    /** One of the player's units, plus where it can go, what it can attack, what it can build and which actions it has. */
    fun unitDetails(
        unit: MapUnitView,
        actions: List<UnitAction>,
        targets: List<AttackableTileView>,
        improvements: List<Pair<TileImprovement, Int>>,
        canPromote: Boolean,
        promotionChoices: List<Promotion>,
    ): JsonObject = buildJsonObject {
        writeOwnUnit(unit)
        put("xp", unit.xp)
        put("xpForNextPromotion", unit.xpForNextPromotion())
        put("canPromote", canPromote)
        putJsonArray("promotions") { unit.getPromotions().forEach { add(it.name) } }
        val forThisType = unit.getAvailablePromotions().map { it.promotion }
        putJsonArray("promotionChoices") {
            for (promotion in promotionChoices) {
                addJsonObject {
                    put("name", promotion.name)
                    put("description", try { promotion.getDescription(forThisType) } catch (_: Exception) { promotion.uniques.joinToString("\n") })
                }
            }
        }
        val attacker = unit.asCombatant()
        putJsonArray("targets") {
            for (target in targets) {
                val tile = target.getTileToAttack().position()
                val from = target.getTileToAttackFrom()
                val defender = target.getCombatant()
                addJsonObject {
                    put("x", tile.x)
                    put("y", tile.y)
                    put("fromX", from.position().x)
                    put("fromY", from.position().y)
                    if (defender != null) {
                        put("name", defender.getCombatantName())
                        put("civ", defender.getCivInfo().civName)
                        put("health", defender.getHealth())
                        put("maxHealth", defender.getMaxHealth())
                        put("isCity", defender.isCity())
                        // Expected damage with average luck, like the numbers in Unciv's battle preview
                        try {
                            put("expectedDamageToDefender", attacker.calculateDamageToDefender(defender, from, 0.5f))
                            put("expectedDamageToAttacker", attacker.calculateDamageToAttacker(defender, from, 0.5f))
                        } catch (_: Exception) {
                            // no preview for unusual targets; the attack itself still works
                        }
                    }
                }
            }
        }
        val unitTile = unit.getTile()
        putJsonObject("tileWork") {
            put("improvement", unitTile.improvement)
            put("inProgress", unitTile.improvementInProgress)
            put("turnsLeft", unitTile.turnsToImprovement)
        }
        putJsonArray("improvements") {
            for ((improvement, turns) in improvements) {
                addJsonObject {
                    put("name", improvement.name)
                    put("turns", turns)
                }
            }
        }
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

    /** Turns until [name] is done in [city], or -1 when that isn't a meaningful number (e.g. "Produce Gold"). */
    private fun safeTurns(city: CityView, name: String, useStoredProduction: Boolean): Int {
        if (PerpetualConstruction.isNamePerpetual(name)) return -1
        return try {
            city.constructions.turnsToConstruction(name, useStoredProduction)
        } catch (_: Exception) {
            -1
        }
    }

    private fun JsonObjectBuilder.writeStats(key: String, stats: Stats) {
        putJsonObject(key) {
            put("food", stats.food)
            put("production", stats.production)
            put("gold", stats.gold)
            put("science", stats.science)
            put("culture", stats.culture)
            put("faith", stats.faith)
        }
    }

    /** A city's growth, yields, queue and everything it could build - what Unciv's city screen shows. */
    fun cityDetails(city: CityView): JsonObject = buildJsonObject {
        city.updateTileStats()
        val constructions = city.constructions
        put("name", city.name)
        put("x", city.location.x)
        put("y", city.location.y)
        put("population", city.getPopulationCount())
        put("foodStored", city.getFoodStored())
        put("foodToGrow", city.getFoodToNextPopulation())
        put("turnsToGrowth", city.getNumTurnsToNewPopulation())
        put("turnsToStarvation", city.getNumTurnsToStarvation())
        put("health", city.getHealth())
        put("maxHealth", city.getMaxHealth())
        put("isCapital", city.isCapital())
        put("isPuppet", city.isPuppet())
        writeStats("yields", city.getCurrentCityStats())
        putJsonArray("queue") {
            for ((index, name) in constructions.constructionQueue.withIndex()) {
                addJsonObject {
                    put("index", index)
                    put("name", name)
                    put("turns", safeTurns(city, name, true))
                }
            }
        }
        putJsonArray("buildings") { city.getBuiltBuildings().forEach { add(it.name) } }

        val ruleset = city.getRuleset()
        putJsonArray("options") {
            for (construction in ruleset.units.values.asSequence() + ruleset.buildings.values.asSequence()) {
                if (!constructions.shouldBeDisplayed(construction)) continue
                val nonPerpetual = construction as INonPerpetualConstruction
                addJsonObject {
                    put("name", construction.name)
                    put("kind", when {
                        construction is Building && construction.isAnyWonder() -> "wonder"
                        construction is Building -> "building"
                        else -> "unit"
                    })
                    val useStored = construction is Building || !constructions.isBeingConstructedOrEnqueued(construction.name)
                    put("turns", safeTurns(city, construction.name, useStored))
                    put("cost", city.getConstructionProductionCost(nonPerpetual))
                    put("buildable", constructions.isBuildable(construction))
                    val goldCost = try { constructions.getStatBuyCost(nonPerpetual, Stat.Gold) } catch (_: Exception) { null }
                    if (goldCost != null) {
                        put("goldCost", goldCost)
                        put("canBuy", constructions.isConstructionPurchaseAllowed(nonPerpetual, Stat.Gold, goldCost))
                    }
                }
            }
            for (perpetual in PerpetualConstruction.perpetualConstructionsMap.values) {
                if (!constructions.shouldBeDisplayed(perpetual)) continue
                addJsonObject {
                    put("name", perpetual.name)
                    put("kind", "perpetual")
                    put("turns", -1)
                    put("buildable", constructions.isBuildable(perpetual))
                }
            }
        }
    }

    /** The whole tech tree from [civ]'s point of view, plus what's being researched. */
    fun techs(civ: Civilization): JsonObject = buildJsonObject {
        val techs = civ.tech
        put("current", techs.currentTechnologyName())
        put("freeTechs", techs.freeTechs)
        put("sciencePerTurn", civ.stats.statsForNextTurn.science)
        putJsonArray("queue") { techs.techsToResearch.forEach { add(it) } }
        putJsonArray("techs") {
            for (tech in civ.gameInfo.ruleset.technologies.values) {
                addJsonObject {
                    put("name", tech.name)
                    put("era", tech.era())
                    put("column", tech.column?.columnNumber ?: 0)
                    put("row", tech.row)
                    put("cost", techs.costOfTech(tech.name))
                    put("progress", techs.researchOfTech(tech.name))
                    put("researched", techs.isResearched(tech.name))
                    put("researchable", techs.canBeResearched(tech.name))
                    put("queueIndex", techs.techsToResearch.indexOf(tech.name))
                    if (!techs.isResearched(tech.name)) put("turns", techs.turnsToTech(tech.name))
                    putJsonArray("prerequisites") { tech.prerequisites.forEach { add(it) } }
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
