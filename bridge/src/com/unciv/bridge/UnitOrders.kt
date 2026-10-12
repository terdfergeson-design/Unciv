package com.unciv.bridge

import com.unciv.logic.automation.unit.UnitAutomation
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapPathing
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Every unit action Unciv's unit panel offers, without its world screen.
 *
 * Most come straight from Unciv's UnitActions. The ones that live in its UI - escort, swap, disband, connect road,
 * skip, and the confirmation popups of pillage and of founding a city where you promised not to - are rebuilt here
 * with the same rules. Actions that need a second pick ("swap with which unit?", "connect a road to where?") list
 * the tiles you can pick ("target": "tile", "targets") and take x, y when done.
 */
object UnitOrders {

    class Entry(
        val type: UnitActionType,
        val title: String,
        val enabled: Boolean,
        val current: Boolean,
        /** Asks "are you sure?" first, with this text */
        val confirm: String? = null,
        /** Tiles to pick from before the action happens */
        val targets: List<Tile>? = null,
        /** Where a moving unit is heading (Show unit destination) */
        val destination: Tile? = null,
        /** Picks a tile like [targets], but any tile may be tried (too many to list; the bridge checks the pick) */
        val anyTile: Boolean = false,
        val run: ((Tile?) -> Unit)? = null,
    )

    /** Unciv's mapped action types, in its order; these are safe to ask for without a world screen. */
    private val mappedTypes = listOf(
        UnitActionType.Transform, UnitActionType.Paradrop, UnitActionType.AirSweep, UnitActionType.SetUp,
        UnitActionType.Guard, UnitActionType.FoundCity, UnitActionType.Repair, UnitActionType.HurryResearch,
        UnitActionType.HurryPolicy, UnitActionType.HurryWonder, UnitActionType.HurryBuilding,
        UnitActionType.ConductTradeMission, UnitActionType.FoundReligion, UnitActionType.EnhanceReligion,
        UnitActionType.CreateImprovement, UnitActionType.SpreadReligion, UnitActionType.RemoveHeresy,
        UnitActionType.TriggerUnique, UnitActionType.AddInCapital, UnitActionType.GiftUnit,
    )

    /** Runs [block] with the unit's civ treated as an AI, which makes Unciv skip its confirmation popups. */
    private fun <T> withoutPopups(unit: MapUnit, block: () -> T): T {
        val civ = unit.civ
        val type = civ.playerType
        civ.playerType = PlayerType.AI
        try {
            return block()
        } finally {
            civ.playerType = type
        }
    }

    private fun fromUnciv(action: UnitAction, run: ((Tile?) -> Unit)? = action.action?.let { invoke -> { _: Tile? -> invoke() } }) =
        Entry(action.type, action.title, action.action != null, action.isCurrentAction, run = run)

    fun list(unit: MapUnit): List<Entry> {
        val tile = unit.getTile()
        val result = ArrayList<Entry>()
        fun tryAdd(what: String, block: () -> Unit) {
            try { block() } catch (ex: Exception) { System.err.println("Unit action $what for ${unit.name}: $ex") }
        }

        for (type in mappedTypes) tryAdd(type.name) {
            for (action in UnitActions.getUnitActions(unit, type)) {
                when (type) {
                    UnitActionType.FoundCity -> result.add(foundCity(unit, tile, action))
                    else -> result.add(fromUnciv(action))
                }
            }
        }
        tryAdd("ConnectRoad") { connectRoad(unit)?.let { result.add(it) } }

        // Unciv's "unmapped" actions, rebuilt in the same order
        if (!unit.isAutomated()) result.add(Entry(UnitActionType.Automate, UnitActionType.Automate.value, unit.hasMovement(), false) {
            unit.automated = true
            UnitAutomation.automateUnitMoves(unit)
        })
        if (unit.isMoving()) result.add(Entry(UnitActionType.StopMovement, UnitActionType.StopMovement.value, true, false) { unit.action = null })
        if (unit.isExploring()) result.add(Entry(UnitActionType.StopExploration, UnitActionType.StopExploration.value, true, false) { unit.action = null })
        if (unit.isAutomated()) result.add(Entry(UnitActionType.StopAutomation, UnitActionType.StopAutomation.value, true, false) {
            unit.action = null
            unit.automated = false
        })
        if (unit.promotions.canBePromoted())   // the client opens its promotion card for this one
            result.add(Entry(UnitActionType.Promote, UnitActionType.Promote.value, unit.hasMovement() && unit.attacksThisTurn == 0, false))
        tryAdd("Upgrade") { for (action in UnitActionsUpgrade.getUpgradeActions(unit)) result.add(fromUnciv(action)) }
        tryAdd("Pillage") { pillage(unit)?.let { result.add(it) } }
        addSleep(unit, tile, result)
        addFortify(unit, result)
        if (!unit.baseUnit.isAirUnit() && !unit.isExploring())
            result.add(Entry(UnitActionType.Explore, UnitActionType.Explore.value, true, false) {
                unit.action = UnitActionType.Explore.value
                if (unit.hasMovement()) UnitAutomation.automatedExplore(unit)
            })
        result.add(Entry(UnitActionType.Skip, UnitActionType.Skip.value, unit.hasMovement(), !unit.due) { unit.due = !unit.due })
        if (unit.isMoving()) tryAdd("ShowUnitDestination") {
            result.add(Entry(UnitActionType.ShowUnitDestination, UnitActionType.ShowUnitDestination.value, true, false,
                destination = unit.getMovementDestination()))
        }
        tryAdd("Escort") { escort(unit)?.let { result.add(it) } }
        tryAdd("Swap") { swap(unit)?.let { result.add(it) } }
        result.add(disband(unit))
        return result
    }

    private fun foundCity(unit: MapUnit, tile: Tile, action: UnitAction): Entry {
        if (action.action == null) return fromUnciv(action)
        // Unciv asks first when the city would break a promise not to settle near someone
        val promised = unit.civ.getKnownCivs().filter { it.isMajorCiv() && !unit.civ.isAtWarWith(it) }.filter { other ->
            other.getDiplomacyManager(unit.civ)?.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs) == true
                && other.cities.any { it.getCenterTile().aerialDistanceTo(tile) <= 6 && other.hasExplored(it.getCenterTile()) }
        }.map { it.getLeaderDisplayName() }.toList()
        val confirm = if (promised.isEmpty()) null else Lang.t("Do you want to break your promise to [${promised.joinToString(", ")}]?")
        return Entry(action.type, action.title, true, action.isCurrentAction, confirm = confirm) {
            val civ = unit.civ
            val before = civ.cities.toSet()
            withoutPopups(unit) {
                UnitActions.getUnitActions(unit, UnitActionType.FoundCity).firstOrNull { it.action != null }?.action?.invoke()
            }
            // Founding runs with the civ treated as an AI (no popups), and Unciv lets an AI's new city pick its own
            // production. A player's new city only does that with "Auto-assign city production" on; otherwise it
            // waits for the player to choose, as in Unciv
            if (civ.isHuman() && !com.unciv.UncivGame.Current.settings.autoAssignCityProduction)
                for (city in civ.cities.filter { it !in before && !it.isPuppet })
                    city.cityConstructions.editQueue { clear() }
        }
    }

    private fun pillage(unit: MapUnit): Entry? {
        // Unciv's own test (UnitActionsPillage.getPillageAction); without it the search below would run on into the
        // actions that need the world screen
        if (unit.isCivilian() || unit.currentTile.getImprovementToPillageName() == null || unit.currentTile.getOwner() == unit.civ) return null
        val raw = withoutPopups(unit) { UnitActions.getUnitActions(unit, UnitActionType.Pillage).firstOrNull() } ?: return null
        val improvement = unit.currentTile.getImprovementToPillageName()
        return Entry(UnitActionType.Pillage, raw.title, raw.action != null, raw.isCurrentAction,
            confirm = improvement?.let { Lang.t("Are you sure you want to pillage this [$it]?") }) {
            withoutPopups(unit) { UnitActions.getUnitActions(unit, UnitActionType.Pillage).firstOrNull { it.action != null }?.action?.invoke() }
        }
    }

    private fun addSleep(unit: MapUnit, tile: Tile, result: MutableList<Entry>) {
        if (unit.isFortified() || unit.canFortify() || unit.isGuarding() || !unit.hasMovement()) return
        if (tile.hasImprovementInProgress() && unit.canBuildImprovement(tile.getTileImprovementInProgress()!!)) return
        result.add(Entry(UnitActionType.Sleep, UnitActionType.Sleep.value, !unit.isSleeping() || unit.isSleepingUntilHealed(), unit.isSleeping() && !unit.isActionUntilHealed()) {
            unit.action = UnitActionType.Sleep.value
        })
        if (unit.health == 100) return
        result.add(Entry(UnitActionType.SleepUntilHealed, UnitActionType.SleepUntilHealed.value,
            !unit.isSleepingUntilHealed() && unit.canHealInCurrentTile(), unit.isSleepingUntilHealed()) {
            unit.action = UnitActionType.SleepUntilHealed.value
        })
    }

    private fun addFortify(unit: MapUnit, result: MutableList<Entry>) {
        if (unit.isFortified()) {
            val type = if (unit.isActionUntilHealed()) UnitActionType.FortifyUntilHealed else UnitActionType.Fortify
            result.add(Entry(type, "{Fortification} ${unit.getFortificationTurns() * 20}%", false, true))
            return
        }
        if (!unit.canFortify() || !unit.hasMovement()) return
        result.add(Entry(UnitActionType.Fortify, UnitActionType.Fortify.value, true, false) { unit.fortify() })
        if (unit.health == 100) return
        result.add(Entry(UnitActionType.FortifyUntilHealed, UnitActionType.FortifyUntilHealed.value, unit.canHealInCurrentTile(), false) {
            unit.fortifyUntilHealed()
        })
    }

    private fun escort(unit: MapUnit): Entry? {
        if (unit.baseUnit.isAirUnit()) return null
        if (unit.getOtherEscortUnit() == null) return null
        return if (!unit.isEscorting()) Entry(UnitActionType.EscortFormation, UnitActionType.EscortFormation.value, true, false) { unit.startEscorting() }
        else Entry(UnitActionType.StopEscortFormation, UnitActionType.StopEscortFormation.value, true, false) { unit.stopEscorting() }
    }

    private fun swap(unit: MapUnit): Entry? {
        if (unit.baseUnit.isAirUnit()) return null
        val tiles = unit.movement.getUnitSwappableTiles().toList()
        if (tiles.isEmpty()) return null
        return Entry(UnitActionType.SwapUnits, UnitActionType.SwapUnits.value, true, false, targets = tiles) { target ->
            if (target == null || !unit.movement.canUnitSwapTo(target)) throw BridgeException("${Gameplay.plain(unit.displayName())} can't swap places with anything there")
            unit.movement.swapMoveToTile(target, true)
            if (unit.isExploring() || unit.isMoving()) unit.action = null
        }
    }

    private fun connectRoad(unit: MapUnit): Entry? {
        if (!unit.cache.hasUniqueToBuildImprovements) return null
        if (unit.currentTile.ruleset.roadImprovement == null) return null
        val civ = unit.civ
        // Only once the civ can build roads somewhere (The Wheel in Civ V)
        val roadsKnown = civ.gameInfo.ruleset.roadImprovement?.let { road -> road.techRequired == null || civ.tech.isResearched(road.techRequired!!) } == true
        return Entry(UnitActionType.ConnectRoad, UnitActionType.ConnectRoad.value, roadsKnown && unit.hasMovement(),
            unit.isAutomatingRoadConnection(), anyTile = true) { target ->
            if (target == null) throw BridgeException("Pick where the road should go")
            if (!target.isLand || target.isImpassible() || !civ.hasExplored(target) || !MapPathing.isValidRoadPathTile(civ, target))
                throw BridgeException("A road can't go there")
            if (unit.movement.getRoadPath(target) == null) throw BridgeException("${Gameplay.plain(unit.displayName())} can't build a road to there")
            unit.automatedRoadConnectionDestination = target.position
            unit.automatedRoadConnectionPath = null
            unit.action = UnitActionType.ConnectRoad.value
            unit.automated = true
            UnitAutomation.automateUnitMoves(unit)
        }
    }

    private fun disband(unit: MapUnit): Entry {
        val text = if (unit.currentTile.getOwner() == unit.civ) Lang.t("Disband this unit for [${unit.baseUnit.getDisbandGold(unit.civ)}] gold?")
            else Lang.t("Do you really want to disband this unit?")
        return Entry(UnitActionType.DisbandUnit, UnitActionType.DisbandUnit.value, unit.hasMovement(), false, confirm = text) {
            unit.disband()
            unit.civ.updateStatsForNextTurn()   // less upkeep
        }
    }

    fun writeEntries(builder: kotlinx.serialization.json.JsonObjectBuilder, entries: List<Entry>) = with(builder) {
        putJsonArray("actions") {
            for (entry in entries) addJsonObject {
                put("type", entry.type.name)
                put("title", Gameplay.stripBrackets(entry.title))   // what the client sends back to pick this one
                put("label", Lang.t(entry.title))                   // what it shows
                put("enabled", entry.enabled)
                put("current", entry.current)
                // The sound Unciv plays for this action (fortify, promote, chimes...), for the client
                put("sound", entry.type.uncivSound.fileName)
                entry.confirm?.let { put("confirm", it) }
                if (entry.anyTile) put("target", "tile")
                entry.targets?.let { targets ->
                    put("target", "tile")
                    putJsonArray("targets") {
                        for (tile in targets) addJsonObject { put("x", tile.position.x); put("y", tile.position.y) }
                    }
                }
                entry.destination?.let { putJsonObject("destination") { put("x", it.position.x); put("y", it.position.y) } }
            }
        }
    }

    /**
     * params: action (type name), title (to tell several of one type apart), x, y (for actions that pick a tile),
     * confirm (true once the player has said yes to the action's "are you sure?").
     */
    fun run(unit: MapUnit, typeName: String, title: String?, target: HexCoord?, confirmed: Boolean): JsonObject {
        val candidates = list(unit).filter { it.type.name == typeName }
        if (candidates.isEmpty()) throw BridgeException("${Gameplay.plain(unit.displayName())} can't do $typeName right now")
        val entry = candidates.firstOrNull { title == null || Gameplay.stripBrackets(it.title) == Gameplay.stripBrackets(title) } ?: candidates.first()
        if (!entry.enabled || entry.run == null) throw BridgeException("${Gameplay.plain(unit.displayName())} can't do ${Gameplay.plain(entry.title)} right now")
        if (entry.confirm != null && !confirmed) return buildJsonObject {
            put("done", false)
            put("confirm", entry.confirm)
        }
        val tile = target?.let { position ->
            if (position !in unit.civ.gameInfo.tileMap) throw BridgeException("($position) is off the map")
            unit.civ.gameInfo.tileMap[position]
        }
        if (entry.targets != null && (tile == null || tile !in entry.targets)) throw BridgeException("Pick one of the highlighted tiles")
        entry.run.invoke(tile)
        return buildJsonObject {
            put("done", true)
            put("action", typeName)
            put("unitStillExists", unit.civ.units.getCivUnits().any { it === unit })
        }
    }
}
