package com.unciv.bridge


import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.SpyAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Unciv's espionage overview: your spies, where they are and what they do, and moving them between cities. */
object Espionage {

    fun overview(civ: Civilization): JsonObject = buildJsonObject {
        val game = civ.gameInfo
        val manager = civ.espionageManager
        put("enabled", game.isEspionageEnabled())
        putJsonArray("spies") {
            for (spy in manager.spyList) addJsonObject {
                put("name", spy.name)
                put("rank", spy.getEffectiveRank())
                put("action", spy.action.displayString)
                put("location", spy.getLocationName())
                spy.getCityOrNull()?.let { put("x", it.location.x); put("y", it.location.y) }
                if (spy.action.showTurns) put("turns", spy.turnsRemainingForAction)
                put("alive", spy.isAlive())
                if (spy.canDoCoup()) {
                    put("canCoup", true)
                    put("coupChance", (spy.getCoupChanceOfSuccess(false) * 100f).toInt())
                    put("stagingCoup", spy.action == SpyAction.Coup)
                }
            }
        }
        putJsonArray("cities") {
            val cities = game.getCities().filter { civ.hasExplored(it.getCenterTile()) }
                .sortedWith(compareBy<City> { it.civ != civ }.thenBy { it.civ.isCityState }.thenBy { it.civ.civName }.thenBy { it.name })
            for (city in cities) addJsonObject {
                put("name", city.name)
                put("civ", city.civ.civName)
                put("x", city.location.x)
                put("y", city.location.y)
                put("isCityState", city.civ.isCityState)
                put("own", city.civ == civ)
                putJsonArray("spies") { manager.getSpiesInCity(city).forEach { add(it.name) } }
                putJsonArray("canMoveHere") { manager.spyList.filter { it.isAlive() && it.canMoveTo(city) }.forEach { add(it.name) } }
                if (promiseWarning(civ, city)) put("promiseWarning", "This may result in breaking your promise to ${city.civ.civName}")
            }
        }
    }

    private fun promiseWarning(civ: Civilization, city: City) = city.civ != civ && city.civ.isMajorCiv() && city.civ.knows(civ)
        && city.civ.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.AgreedToNotSendSpies) == true

    /** params: spy (name), city (name; missing = back to the hideout), confirm. */
    fun move(civ: Civilization, params: JsonObject): JsonObject {
        val spy = civ.espionageManager.spyList.firstOrNull { it.name == params.requireStr("spy") && it.isAlive() }
            ?: throw BridgeException("No spy called ${params.str("spy")}")
        val cityName = params.str("city")
        val city = cityName?.let { name -> civ.gameInfo.getCities().firstOrNull { it.name == name } ?: throw BridgeException("No city $name") }
        if (city != null) {
            if (!spy.canMoveTo(city)) throw BridgeException("${spy.name} can't go to ${city.name}")
            if (promiseWarning(civ, city) && params.bool("confirm") != true)
                return buildJsonObject { put("done", false); put("confirm", "This may result in breaking your promise to ${city.civ.civName}") }
        }
        spy.moveTo(city)
        return buildJsonObject { put("done", true); put("espionage", overview(civ)) }
    }

    /** params: spy, confirm. Stages a coup in the city-state the spy is in (or calls it off). */
    fun coup(civ: Civilization, params: JsonObject): JsonObject {
        val spy = civ.espionageManager.spyList.firstOrNull { it.name == params.requireStr("spy") && it.isAlive() }
            ?: throw BridgeException("No spy called ${params.str("spy")}")
        if (spy.action == SpyAction.Coup) {
            spy.setAction(SpyAction.CounterIntelligence, 10)
            return buildJsonObject { put("done", true); put("espionage", overview(civ)) }
        }
        if (!spy.canDoCoup()) throw BridgeException("${spy.name} can't stage a coup there")
        if (params.bool("confirm") != true) return buildJsonObject {
            put("done", false)
            put("confirm", "Do you want to stage a coup in ${spy.getCity().civ.civName} with a ${(spy.getCoupChanceOfSuccess(false) * 100f).toInt()}% chance of success?")
        }
        spy.setAction(SpyAction.Coup, 1)
        return buildJsonObject { put("done", true); put("espionage", overview(civ)) }
    }

}
