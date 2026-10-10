package com.unciv.bridge

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.stats.Stat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test helpers, only when the bridge is started with --debug (never by the client's launcher): they set up
 * situations that take a long game to reach (a free great person, a prophet, a city to capture...).
 */
object DebugCommands {
    var enabled = false

    fun run(game: GameInfo, viewer: Civilization, params: JsonObject): JsonObject {
        if (!enabled) throw BridgeException("Debug commands are off (start the bridge with --debug)")
        val civ = params.str("civ")?.let { name -> game.civilizations.firstOrNull { it.civName == name } ?: throw BridgeException("No civ $name") } ?: viewer
        val result = when (val command = params.requireStr("command")) {
            "addStat" -> {
                val stat = Stat.valueOf(params.requireStr("stat"))
                civ.addStat(stat, params.requireInt("amount"))
                "ok"
            }
            "addTech" -> { civ.tech.addTechnology(params.requireStr("tech")); "ok" }
            "spawnUnit" -> {
                val at = HexCoord(params.requireInt("x"), params.requireInt("y"))
                // Off the map, Unciv fails halfway and leaves a unit with no tile behind, which breaks getState
                if (at !in game.tileMap) throw BridgeException("($at) is off the map")
                val unitName = params.requireStr("unit")
                if (unitName !in game.ruleset.units) throw BridgeException("No unit called $unitName")
                val unit = civ.units.placeUnitNearTile(at, unitName)
                    ?: throw BridgeException("Couldn't place it")
                unit.id.toString()
            }
            "freeGreatPerson" -> { civ.greatPeople.freeGreatPeople += 1; "ok" }
            "meet" -> {
                val other = game.civilizations.first { it.civName == params.requireStr("other") }
                if (!civ.knows(other)) civ.diplomacyFunctions.makeCivilizationsMeet(other)
                "ok"
            }
            "war" -> {
                val other = game.civilizations.first { it.civName == params.requireStr("other") }
                if (!civ.knows(other)) civ.diplomacyFunctions.makeCivilizationsMeet(other)
                civ.getDiplomacyManager(other)!!.declareWar()
                "ok"
            }
            // x, y, health (and unitId to pick one of a stack): hurt a unit, so one attack finishes it off
            "unitHealth" -> {
                val at = HexCoord(params.requireInt("x"), params.requireInt("y"))
                if (at !in game.tileMap) throw BridgeException("($at) is off the map")
                val units = game.tileMap[at].getUnits().toList()
                if (units.isEmpty()) throw BridgeException("No unit at ($at)")
                val id = params.int("unitId")
                val unit = (if (id == null) units.first() else units.firstOrNull { it.id == id })
                    ?: throw BridgeException("No unit $id at ($at)")
                unit.health = params.requireInt("health")
                "ok"
            }
            "cityHealth" -> {
                val city = game.getCities().first { it.name == params.requireStr("city") }
                city.health = params.requireInt("health")
                "ok"
            }
            "reveal" -> {
                for (tile in game.tileMap.values) tile.setExplored(civ, true)
                civ.cache.updateViewableTiles()
                "ok"
            }
            "popupAlert" -> {
                civ.popupAlerts.add(com.unciv.logic.civilization.PopupAlert(
                    com.unciv.logic.civilization.AlertType.valueOf(params.requireStr("type")), params.requireStr("value")))
                "ok"
            }
            "votingFlag" -> {
                civ.flagsCountdown[com.unciv.logic.civilization.CivFlags.TurnsTillNextDiplomaticVote.name] = 0
                "ok"
            }
            else -> throw BridgeException("Unknown debug command '$command'")
        }
        return buildJsonObject { put("result", result) }
    }
}
