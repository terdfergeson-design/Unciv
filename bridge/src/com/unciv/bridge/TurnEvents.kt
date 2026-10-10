package com.unciv.bridge

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.view.GameView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What happened while the other players took their turns, as far as the player could see it, so the client
 * can play it back on the table: units moving, and fights with their damage and losses.
 *
 * Unciv doesn't keep an ordered log of the AI's actions, so this compares the game before and after
 * [GameInfo.nextTurn]: where each unit was and how healthy, against where it is now; plus the attacks Unciv
 * remembers for each unit and civ since the start of its turn (the same ones its movement-arrow overlay draws,
 * filtered by the View API's [GameView.getVisibleAttacks] - an attack shows if either end was in sight).
 * Damage is the health a defender lost, split evenly when several attacks hit the same tile.
 */
object TurnEvents {
    private const val MAX_EVENTS = 300

    class UnitSnap(val id: Int, val name: String, val civ: String, val position: HexCoord, val health: Int,
                   val visible: Boolean, val civilian: Boolean)

    class CitySnap(val name: String, val civ: String, val position: HexCoord, val health: Int)

    class Snapshot(val units: Map<Int, UnitSnap>, val cities: Map<HexCoord, CitySnap>)

    fun snapshot(game: GameInfo, viewer: Civilization): Snapshot {
        val units = HashMap<Int, UnitSnap>()
        for (civ in game.civilizations) for (unit in civ.units.getCivUnits()) {
            val tile = unit.getTile()
            units[unit.id] = UnitSnap(unit.id, unit.baseUnit.name, civ.civName, tile.position, unit.health,
                tile in viewer.viewableTiles && unit.isVisibleTo(viewer), unit.isCivilian())
        }
        val cities = HashMap<HexCoord, CitySnap>()
        for (civ in game.civilizations) for (city in civ.cities)
            cities[city.getCenterTile().position] = CitySnap(city.name, civ.civName, city.getCenterTile().position, city.health)
        return Snapshot(units, cities)
    }

    private class Attack(val civ: Civilization, val unitId: Int?, val name: String, val sound: String, val ranged: Boolean,
                         val from: HexCoord, val target: HexCoord, val isCity: Boolean)

    /** The events since [before] was taken, in the order the civs played. */
    fun events(game: GameInfo, viewer: Civilization, before: Snapshot): JsonArray {
        val gameView = GameView(game, viewer)
        val visibleAttacks = gameView.getVisibleAttacks().toHashSet()
        val nowUnits = HashMap<Int, com.unciv.logic.map.mapunit.MapUnit>()
        for (civ in game.civilizations) for (unit in civ.units.getCivUnits()) nowUnits[unit.id] = unit

        // Attacks, per civ in turn order
        val attacks = ArrayList<Attack>()
        for (civ in game.civilizations) {
            if (civ == viewer) continue   // the player's own fights were played as they happened
            for (unit in civ.units.getCivUnits()) {
                val position = unit.getTile().position
                for (targetCoord in unit.attacksSinceTurnStart) {
                    if ((position to targetCoord) !in visibleAttacks) continue
                    val base = unit.baseUnit
                    attacks.add(Attack(civ, unit.id, base.name, base.attackSound ?: "", base.isRanged(),
                        attackedFrom(game, position, targetCoord, before.units[unit.id]?.position, base.isRanged()), targetCoord, false))
                }
            }
            for (memory in civ.attacksSinceTurnStart) {
                if ((memory.source to memory.target) !in visibleAttacks) continue
                val unitName = memory.attackingUnit
                val base = unitName?.let { game.ruleset.units[it] }
                attacks.add(Attack(civ, null, unitName ?: (game.tileMap[memory.source].getCity()?.name ?: "City"),
                    base?.attackSound ?: "cannon", base?.isRanged() ?: true, memory.source, memory.target, unitName == null))
            }
        }

        // How much each attacked tile's defender lost, and whether it died
        val hitsPerTile = attacks.groupingBy { it.target }.eachCount()
        val hitsSoFar = HashMap<HexCoord, Int>()

        return buildJsonArray {
            var count = 0
            val movedUnits = HashSet<Int>()
            for (civ in game.civilizations) {
                // Moves first, then this civ's fights (Unciv doesn't say how they interleave)
                for (unit in civ.units.getCivUnits()) {
                    val was = before.units[unit.id] ?: continue
                    val now = unit.getTile().position
                    if (was.position == now) continue
                    val seen = was.visible || (unit.getTile() in viewer.viewableTiles && unit.isVisibleTo(viewer))
                    if (!seen || count >= MAX_EVENTS) continue
                    // A melee attacker that advanced: walk to where it attacked from; the advance comes after the fight
                    val attack = attacks.firstOrNull { it.unitId == unit.id }
                    val stop = attack?.from ?: now
                    if (stop != was.position) {
                        addJsonObject {
                            put("type", "move")
                            put("unit", unit.id)
                            put("name", unit.baseUnit.name)
                            put("civ", civ.civName)
                            putCoord("from", was.position)
                            putCoord("to", stop)
                        }
                        count++
                    }
                    movedUnits.add(unit.id)
                }
                for (attack in attacks.filter { it.civ == civ }) {
                    if (count >= MAX_EVENTS) break
                    val hits = hitsPerTile[attack.target] ?: 1
                    val hitNumber = (hitsSoFar[attack.target] ?: 0) + 1
                    hitsSoFar[attack.target] = hitNumber
                    val lastHit = hitNumber == hits
                    addJsonObject {
                        put("type", "attack")
                        attack.unitId?.let { put("attacker", it) }
                        put("name", attack.name)
                        put("civ", attack.civ.civName)
                        put("sound", attack.sound)
                        put("ranged", attack.ranged || attack.isCity)
                        put("city", attack.isCity)
                        putCoord("from", attack.from)
                        putCoord("to", attack.target)
                        // the defender: a city (Unciv attacks the city, not its garrison), else a unit (military first)
                        val city = before.cities[attack.target]
                        val defenders = before.units.values.filter { it.position == attack.target && it.civ != attack.civ.civName }
                        val defender = if (city != null) null else defenders.firstOrNull { !it.civilian } ?: defenders.firstOrNull()
                        if (defender != null) {
                            val alive = nowUnits[defender.id]
                            val lost = defender.health - (alive?.health ?: 0)
                            put("defender", defender.id)
                            put("defenderName", defender.name)
                            put("defenderCiv", defender.civ)
                            put("damage", maxOf(0, lost) / hits)
                            put("defenderDied", lastHit && (alive == null || alive.civ.civName != defender.civ))
                        } else if (city != null) {
                            val nowCity = game.tileMap[attack.target].getCity()
                            put("defenderName", city.name)
                            put("defenderCiv", city.civ)
                            put("defenderCity", true)
                            put("damage", maxOf(0, city.health - (nowCity?.health ?: 0)) / hits)
                            put("cityCaptured", lastHit && nowCity?.civ?.civName != city.civ)
                        }
                        attack.unitId?.let { id ->
                            val was = before.units[id]
                            val alive = nowUnits[id]
                            if (was != null) put("damageTaken", maxOf(0, was.health - (alive?.health ?: 0)))
                        }
                        if (attack.unitId == null && !attack.isCity) put("attackerDied", true)   // remembered by the civ: the unit is gone
                    }
                    count++
                    // ...then the melee advance onto the tile it took
                    val unit = attack.unitId?.let { nowUnits[it] }
                    if (unit != null && unit.getTile().position != attack.from && attacks.lastOrNull { it.unitId == unit.id } === attack) {
                        addJsonObject {
                            put("type", "move")
                            put("unit", unit.id)
                            put("name", unit.baseUnit.name)
                            put("civ", civ.civName)
                            putCoord("from", attack.from)
                            putCoord("to", unit.getTile().position)
                        }
                        count++
                    }
                }
            }
        }
    }

    /** Where a unit attacked [target] from: where it is now, unless it's a melee unit that isn't next to the target
     *  any more (it advanced onto it, or moved on afterwards), in which case the neighbouring tile closest to where
     *  it started the turn. */
    private fun attackedFrom(game: GameInfo, position: HexCoord, target: HexCoord, start: HexCoord?, ranged: Boolean): HexCoord {
        // A melee unit fights from next door: if it's elsewhere now it advanced, or moved on after attacking
        if (position != target && (ranged || HexMath.getDistance(position, target) <= 1)) return position
        val neighbours = game.tileMap[target].neighbors.map { it.position }.toList()
        if (neighbours.isEmpty()) return position
        val origin = start ?: return neighbours.first()
        return neighbours.minBy { HexMath.getDistance(it, origin) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCoord(key: String, c: HexCoord) {
        putJsonArray(key) {
            add(kotlinx.serialization.json.JsonPrimitive(c.x))
            add(kotlinx.serialization.json.JsonPrimitive(c.y))
        }
    }
}
