package com.unciv.bridge

import com.unciv.logic.GameInfo
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.CityFlags
import com.unciv.logic.city.CityResources
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Notification
import com.unciv.logic.civilization.NotificationAction
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.UpgradeUnitAction
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.overviewscreen.WonderInfo
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActionsUpgrade
import com.unciv.view.GameView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.lang.reflect.Modifier
import kotlin.math.roundToInt

/**
 * Unciv's Empire Overview screen (its "stats and info" screen), one tab at a time, from [civ]'s point of view:
 * cities, stats, trades, units, politics, resources, religion, wonders and notifications - the same content and
 * the same rules about what you're allowed to know as Unciv's own tabs (ui/screens/overviewscreen).
 */
object OverviewWriter {
    val TABS = listOf("cities", "stats", "trades", "units", "politics", "resources", "religion", "wonders", "notifications", "espionage")

    fun overview(game: GameInfo, civ: Civilization, tab: String): JsonObject = buildJsonObject {
        if (tab !in TABS) throw BridgeException("Unknown overview tab '$tab' - one of ${TABS.joinToString()}")
        putJsonArray("tabs") {
            for (t in TABS) addJsonObject { put("id", t); put("state", tabState(game, civ, t)) }
        }
        put("tab", tab)
        put("turn", game.turns)
        when (tab) {
            "cities" -> cities(game, civ)
            "stats" -> stats(civ)
            "trades" -> trades(civ)
            "units" -> units(game, civ)
            "politics" -> politics(game, civ)
            "resources" -> resources(game, civ)
            "religion" -> religion(game, civ)
            "wonders" -> wonders(civ)
            "notifications" -> notifications(game, civ)
            "espionage" -> for ((key, value) in Espionage.overview(civ)) put(key, value)
        }
    }

    /** normal / disabled / hidden, as Unciv's EmpireOverviewCategories decides. */
    private fun tabState(game: GameInfo, civ: Civilization, tab: String): String = when (tab) {
        "cities" -> if (civ.cities.isEmpty()) "disabled" else "normal"
        "trades" -> if (civ.diplomacy.values.all { it.trades.isEmpty() } &&
            civ.diplomacy.values.none { d -> d.otherCiv.tradeRequests.any { it.requestingCiv == civ.civID } }) "disabled" else "normal"
        "units" -> if (civ.units.getCivUnits().none()) "disabled" else "normal"
        "politics" -> if (civ.diplomacy.isEmpty()) "disabled" else "normal"
        "resources" -> if (civ.detailedCivResources.none { it.resource.resourceType != ResourceType.Bonus }) "disabled" else "normal"
        "religion" -> when {
            !game.isReligionEnabled() -> "hidden"
            game.religions.isEmpty() -> "disabled"
            else -> "normal"
        }
        "wonders" -> if (civ.naturalWonders.isEmpty() && civ.cities.isEmpty()) "disabled" else "normal"
        "notifications" -> if (civ.notifications.isEmpty() && civ.notificationsLog.isEmpty()) "disabled" else "normal"
        "espionage" -> when {
            !game.isEspionageEnabled() -> "hidden"
            civ.espionageManager.spyList.isEmpty() -> "disabled"
            else -> "normal"
        }
        else -> "normal"
    }

    private fun JsonObjectBuilder.colours(other: Civilization) {
        put("nation", other.nation.name)
        put("outerColor", other.nation.getOuterColor().toString())
        put("innerColor", other.nation.getInnerColor().toString())
        put("isCityState", other.isCityState)
        if (other.isCityState) put("cityStateType", other.cityStateType.name)
    }

    // region cities

    private fun JsonObjectBuilder.cities(game: GameInfo, civ: Civilization) {
        val gameView = GameView(game, civ)
        val religionEnabled = civ.gameInfo.isReligionEnabled()
        val cityWide = civ.gameInfo.ruleset.tileResources.values.filter {
            it.isCityWide && it.getMatchingUniques(UniqueType.NotShownOnWorldScreen, civ.state).none()
        }
        put("religionEnabled", religionEnabled)
        putJsonArray("cityWideResources") { cityWide.forEach { add(it.name) } }
        putJsonArray("cities") {
            for (city in civ.cities) addJsonObject {
                put("name", city.name)
                put("x", city.location.x)
                put("y", city.location.y)
                put("isCapital", city.isCapital())
                put("status", when {
                    city.isBeingRazed -> "razing"
                    city.isInResistance() -> "resistance"
                    city.isPuppet -> "puppet"
                    else -> ""
                })
                val current = city.cityConstructions.currentConstructionName()
                put("construction", current)
                put("constructionTurns", if (current.isEmpty() || PerpetualConstruction.isNamePerpetual(current)) -1
                    else try { city.cityConstructions.turnsToConstruction(current) } catch (_: Exception) { -1 })
                put("population", city.population.population)
                val stats = city.cityStats.currentCityStats
                for (stat in listOf(Stat.Food, Stat.Gold, Stat.Science, Stat.Production, Stat.Culture, Stat.Faith))
                    put(stat.name.lowercase(), stats[stat].roundToInt())
                put("happiness", city.cityStats.happinessList.values.sum().roundToInt())
                put("wltk", city.isWeLoveTheKingDayActive())
                if (city.isWeLoveTheKingDayActive()) put("wltkTurns", city.getFlag(CityFlags.WeLoveTheKing))
                if (city.demandedResource.isNotEmpty()) put("demanding", city.demandedResource)
                city.getGarrison()?.let { unit -> putJsonObject("garrison") { put("id", unit.id); put("name", unit.name) } }
                put("defense", CityCombatant(city).getDefendingStrength())
                put("health", city.health)
                put("maxHealth", gameView.getCityView(city).getMaxHealth())
                if (religionEnabled) city.religion.getMajorityReligion()?.let {
                    put("religion", it.getReligionDisplayName())
                    put("holyCity", city.religion.religionThisIsTheHolyCityOf == it.name)
                }
                putJsonObject("resources") {
                    val supply = CityResources.getCityResourcesAvailableToCity(city)
                    for (resource in cityWide)
                        put(resource.name, supply.filter { it.resource == resource }.sumOf { it.amount } + city.resourceStockpiles[resource.name])
                }
            }
        }
    }

    // region stats

    private fun JsonObjectBuilder.stats(civ: Civilization) {
        val statMap = civ.stats.getStatMapForNextTurn()
        putJsonObject("happiness") { for ((source, value) in civ.stats.getHappinessBreakdown()) put(source, value) }
        val religionEnabled = civ.gameInfo.isReligionEnabled()
        put("religionEnabled", religionEnabled)
        for (stat in listOf(Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)) {
            if (stat == Stat.Faith && !religionEnabled) continue
            putJsonObject(stat.name.lowercase()) {
                for ((source, stats) in statMap) if (stats[stat].roundToInt() != 0) put(source, stats[stat])
            }
        }
        if (civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.ConvertGoldToScience))
            put("goldToScience", civ.tech.goldPercentConvertedToScience)
        val perTurn = civ.greatPeople.getGreatPersonPointsForNextTurn()
        putJsonArray("greatPeople") {
            for ((name, points) in civ.greatPeople.greatPersonPointsCounter) addJsonObject {
                put("name", name)
                put("points", points)
                put("needed", civ.greatPeople.getPointsRequiredForGreatPerson(name))
                put("perTurn", perTurn[name])
            }
        }
        putJsonArray("greatGenerals") {
            for ((name, points) in civ.greatPeople.greatGeneralPointsCounter) addJsonObject {
                put("name", name)
                put("points", points)
                put("needed", civ.greatPeople.pointsForNextGreatGeneralCounter[name])
            }
        }
        putJsonObject("score") { for ((source, value) in civ.calculateScoreBreakdown()) put(source, value) }
    }

    // region trades

    private fun JsonObjectBuilder.trades(civ: Civilization) {
        putJsonArray("pending") {
            for (diplomacy in civ.diplomacy.values)
                for (request in diplomacy.otherCiv.tradeRequests.filter { it.requestingCiv == civ.civID })
                    add(tradeJson(request.trade.reverse(), diplomacy.otherCiv))
        }
        putJsonArray("current") {
            for (diplomacy in civ.diplomacy.values)
                for (trade in diplomacy.trades) add(tradeJson(trade, diplomacy.otherCiv))
        }
    }

    private fun tradeJson(trade: Trade, other: Civilization): JsonObject = buildJsonObject {
        put("civ", other.civName)
        colours(other)
        putJsonArray("ours") { trade.ourOffers.forEach { add(it.getOfferText()) } }
        putJsonArray("theirs") { trade.theirOffers.forEach { add(it.getOfferText()) } }
    }

    // region units

    private fun JsonObjectBuilder.units(game: GameInfo, civ: Civilization) {
        val stats = civ.stats
        putJsonObject("supply") {
            put("base", stats.getBaseUnitSupply())
            put("cities", stats.getUnitSupplyFromCities())
            put("population", stats.getUnitSupplyFromPop())
            put("total", stats.getUnitSupply())
            put("inUse", civ.units.getCivUnitsSize())
            put("deficit", stats.getUnitSupplyDeficit())
            put("productionPenalty", stats.getUnitSupplyProductionPenalty().toInt())
        }
        val gameView = GameView(game, civ)
        putJsonArray("units") {
            for (unit in civ.units.getCivUnits()) {
                val view = gameView.getMapUnitView(unit)
                addJsonObject {
                    put("id", unit.id)
                    put("name", Gameplay.plain(view.displayName()))
                    put("baseUnit", unit.baseUnit.name)
                    unit.instanceName?.let { put("instanceName", it) }
                    put("unitType", unit.baseUnit.unitType)
                    put("x", unit.getTile().position.x)
                    put("y", unit.getTile().position.y)
                    put("civilian", unit.isCivilian())
                    put("idle", view.isIdle())
                    val worker = if (!view.hasMovement() || !view.canBuildCurrentImprovement()) null else view.getImprovementInProgress()
                    val action = when {
                        unit.action == null -> worker
                        view.isFortified() -> "Fortify"
                        view.isGuarding() -> "Guard"
                        view.isMoving() -> "Moving"
                        view.isAutomated() && worker != null -> "$worker (automated)"
                        else -> unit.action
                    }
                    if (action != null) put("action", action)
                    put("strength", unit.baseUnit.strength)
                    put("rangedStrength", unit.baseUnit.rangedStrength)
                    put("movement", view.getMovementString())
                    put("maxMovement", view.getMaxMovement())
                    unit.getTile().getTilesInDistance(3).firstOrNull { it.isCityCenter() }?.getCity()?.let {
                        put("closestCity", it.name)
                        put("inCity", unit.getTile().isCityCenter())
                    }
                    putJsonArray("promotions") { view.getPromotionNames().forEach { add(it) } }
                    put("canPromote", view.canBePromoted())
                    put("health", view.unitHealth)
                    if (!unit.isCivilian()) {
                        put("xp", view.xp)
                        put("xpForNextPromotion", view.xpForNextPromotion())
                    }
                    putJsonArray("upgrades") {
                        val upgrades = try { UnitActionsUpgrade.getUpgradeActionAnywhere(unit).toList() } catch (_: Exception) { emptyList() }
                        for (upgrade in upgrades) {
                            val u = upgrade as? UpgradeUnitAction ?: continue
                            addJsonObject {
                                put("to", u.unitToUpgradeTo.name)
                                put("cost", u.goldCostOfUpgrade)
                                put("enabled", u.action != null && civ.isCurrentPlayer())
                            }
                        }
                    }
                }
            }
        }
    }

    /** Upgrades [unitId] (anywhere, like the Units tab) to [to] (or its only upgrade). */
    fun upgradeUnit(civ: Civilization, unitId: Int, to: String?) {
        val unit = civ.units.getCivUnits().firstOrNull { it.id == unitId } ?: throw BridgeException("You have no unit with id $unitId")
        val upgrades = UnitActionsUpgrade.getUpgradeActionAnywhere(unit).mapNotNull { it as? UpgradeUnitAction }.toList()
        val upgrade = upgrades.firstOrNull { to == null || it.unitToUpgradeTo.name == to }
            ?: throw BridgeException("${unit.name} can't be upgraded${if (to != null) " to $to" else ""}")
        val action = upgrade.action
            ?: throw BridgeException("Can't upgrade to ${upgrade.unitToUpgradeTo.name} now (${upgrade.goldCostOfUpgrade} gold, resources or moves needed)")
        action.invoke()
    }

    // region politics

    private fun JsonObjectBuilder.politics(game: GameInfo, civ: Civilization) {
        val wonders = try { WonderInfo().collectInfo(civ).toList() } catch (_: Exception) { emptyList() }
        fun nameOf(other: Civilization) = if (civ.knows(other) || other == civ) other.civName else "an unknown civilization"
        fun JsonObjectBuilder.relations(key: String, flag: DiplomacyFlags, c: Civilization, others: List<Civilization>) {
            putJsonArray(key) {
                for (other in others) {
                    val manager = c.getDiplomacyManager(other) ?: continue
                    if (!manager.hasFlag(flag)) continue
                    addJsonObject { put("civ", nameOf(other)); put("turns", manager.getFlag(flag)) }
                }
            }
        }
        val known = civ.diplomacyFunctions.getKnownCivsSorted(includeCityStates = false)
        putJsonArray("civs") {
            for (c in sequenceOf(civ) + known) addJsonObject {
                put("name", c.civName)
                put("leader", c.nation.leaderName)
                put("era", c.getEra().name)
                put("defeated", c.isDefeated())
                put("score", c.calculateTotalScore().toInt())
                colours(c)
                putJsonArray("policies") {
                    for (branch in c.policies.branches) if (c.policies.isAdopted(branch.name)) addJsonObject {
                        put("branch", branch.name)
                        put("count", 1 + branch.policies.count { it.policyBranchType != PolicyBranchType.BranchComplete && c.policies.isAdopted(it.name) })
                    }
                }
                putJsonArray("wonders") {
                    for (w in wonders) if (w.civ == c) addJsonObject {
                        put("name", w.name)
                        val location = w.location
                        if (location != null && location.isVisible(civ)) { put("x", location.position.x); put("y", location.position.y) }
                    }
                }
                if (civ.knows(c) || c == civ) {
                    val others = c.getKnownCivs().toList()
                    putJsonArray("wars") { for (other in others) if (c.isAtWarWith(other)) add(nameOf(other)) }
                    putJsonArray("defensivePacts") {
                        for (other in others) {
                            val manager = c.getDiplomacyManager(other) ?: continue
                            if (manager.hasFlag(DiplomacyFlags.DefensivePact))
                                addJsonObject { put("civ", nameOf(other)); put("turns", manager.getFlag(DiplomacyFlags.DefensivePact)) }
                        }
                    }
                    putJsonArray("friends") {
                        for (other in others) {
                            val manager = c.getDiplomacyManager(other) ?: continue
                            if (!manager.hasFlag(DiplomacyFlags.DefensivePact) && manager.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
                                addJsonObject { put("civ", nameOf(other)); put("turns", manager.getFlag(DiplomacyFlags.DeclarationOfFriendship)) }
                        }
                    }
                    relations("denounced", DiplomacyFlags.Denunciation, c, others)
                    putJsonArray("alliedCityStates") {
                        for (cityState in game.getAliveCityStates())
                            if (cityState.getDiplomacyManager(c)?.isRelationshipLevelEQ(RelationshipLevel.Ally) == true) add(nameOf(cityState))
                    }
                }
            }
        }
        // The "ball of yarn": everyone you know (and you), with a line for every pair that knows each other
        putJsonObject("diagram") {
            val shown = (sequenceOf(civ) + civ.getKnownCivs().filter { !it.isDefeated() && !it.isBarbarian && !it.isSpectator() })
                .distinct().toList()
            putJsonArray("civs") {
                for (c in shown) addJsonObject { put("name", c.civName); colours(c) }
            }
            putJsonArray("lines") {
                for ((i, c) in shown.withIndex()) for (other in shown.drop(i + 1)) {
                    val manager = c.diplomacy[other.civID] ?: continue
                    addJsonObject {
                        put("a", c.civName)
                        put("b", other.civName)
                        put("relation", relationOf(manager, c, other))
                    }
                }
            }
        }
        put("civsInGame", if (civ.shouldHideCivCount()) -1 else game.civilizations.count { it.isMajorCiv() })
    }

    /** As Unciv's politics diagram: War, DefensivePact, or a relationship level (Ally, Friend, Neutral, ...). */
    private fun relationOf(manager: DiplomacyManager, c: Civilization, other: Civilization): String = when {
        manager.diplomaticStatus == DiplomaticStatus.War -> "War"
        manager.diplomaticStatus == DiplomaticStatus.DefensivePact && !(c.isCityState || other.isCityState) -> "DefensivePact"
        c.isHuman() && other.isHuman() && manager.hasModifier(DiplomaticModifiers.DeclarationOfFriendship) -> RelationshipLevel.Friend.name
        (c.isCityState && c.allyCiv == other) || (other.isCityState && other.allyCiv == c) -> RelationshipLevel.Ally.name
        else -> manager.relationshipLevel().name
    }

    // region resources

    private fun JsonObjectBuilder.resources(game: GameInfo, civ: Civilization) {
        val drill = civ.detailedCivResources
        val extra = extraDrilldown(game, civ)
        val resources: List<TileResource> = (drill.asSequence() + extra.asSequence()).map { it.resource }
            .filter {
                it.resourceType != ResourceType.Bonus &&
                    !it.hasUnique(UniqueType.NotShownOnWorldScreen, civ.state) &&
                    !it.isCityWide
            }
            .distinct()
            .sortedWith(compareBy<TileResource> { it.resourceType }.thenBy { it.name })
            .toList()
        val origins = drill.origins()
        val extraOrigins = listOf("Unimproved", "CelebratingWLTK", "DemandingWLTK", "TradeOffer", "Stockpile")
            .filter { o -> extra.any { it.origin == o } }
        putJsonArray("origins") { origins.forEach { add(it) } }
        putJsonArray("extraOrigins") { extraOrigins.forEach { add(it) } }
        putJsonArray("resources") {
            for (resource in resources) addJsonObject {
                put("name", resource.name)
                put("type", resource.resourceType.name)
                put("stockpiled", resource.isStockpiled)
                putJsonObject("amounts") { for (origin in origins) drill.get(resource, origin)?.let { put(origin, it.amount) } }
                put("total", drill.asSequence().filter { it.resource == resource }.sumOf { it.amount })
                putJsonObject("extra") { for (origin in extraOrigins) extra.get(resource, origin)?.let { put(origin, it.amount) } }
            }
        }
    }

    /** The extra rows of Unciv's Resources tab: unimproved, WLTK, trade offers, stockpiles, known-but-missing strategics. */
    private fun extraDrilldown(game: GameInfo, civ: Civilization): ResourceSupplyList {
        val list = ResourceSupplyList(keepZeroAmounts = true)
        fun countAsUnimproved(tile: com.unciv.logic.map.tile.Tile): Boolean {
            val resource = tile.tileResource ?: return false
            return civ.canSeeResource(resource) && resource.resourceType != ResourceType.Bonus && !tile.providesResources(civ)
        }
        fun addUnimproved(city: com.unciv.logic.city.City) {
            for (tile in city.getTiles()) {
                val resource = tile.tileResource ?: continue
                if (countAsUnimproved(tile)) list.add(resource, "Unimproved")
            }
        }
        for (city in civ.cities) {
            if (city.demandedResource.isNotEmpty()) {
                val resource = game.ruleset.tileResources[city.demandedResource]
                if (resource != null) list.add(resource, if (city.isWeLoveTheKingDayActive()) "CelebratingWLTK" else "DemandingWLTK")
            }
            addUnimproved(city)
        }
        for (other in civ.getKnownCivs()) {
            for (trade in other.tradeRequests.filter { it.requestingCiv == civ.civID })
                for (offer in trade.trade.theirOffers.filter { it.type == TradeOfferType.Strategic_Resource || it.type == TradeOfferType.Luxury_Resource })
                    game.ruleset.tileResources[offer.name]?.let { list.add(it, "TradeOffer", offer.amount) }
            if (!other.isCityState || other.allyCiv != civ) continue
            for (city in other.cities) addUnimproved(city)
        }
        for (resource in game.ruleset.tileResources.values) {
            if (resource.resourceType == ResourceType.Strategic && civ.canSeeResource(resource)) list.add(resource, "No source", 0)
        }
        for (name in civ.resourceStockpiles.keys) {
            val resource = game.ruleset.tileResources[name] ?: continue
            if (resource.hasUnique(UniqueType.NotShownOnWorldScreen, civ.state)) continue
            list.add(resource, "Stockpile", civ.getResourceAmount(resource))
        }
        return list
    }

    // region religion

    private fun JsonObjectBuilder.religion(game: GameInfo, civ: Civilization) {
        val manager = civ.religionManager
        put("enabled", game.isReligionEnabled())
        put("toFound", if (civ.shouldHideCivCount()) -1 else manager.remainingFoundableReligions())
        putJsonArray("toFoundBreakdown") {
            for ((text, value) in manager.remainingFoundableReligionsBreakdown()) addJsonObject { put("text", text); put("value", value) }
        }
        if (manager.canGenerateProphet(ignoreFaithAmount = true)) {
            manager.getGreatProphetEquivalent()?.let { put("prophet", it.name) }
            put("faithForProphet", manager.faithForNextGreatProphet() + 1)
        }
        put("status", manager.religionState.toString())
        putJsonArray("religions") {
            for (religion in game.religions.values) addJsonObject {
                put("name", religion.name)
                put("displayName", religion.getReligionDisplayName())
                put("icon", religion.getIconName())
                put("pantheon", religion.isPantheon())
                val founder = religion.foundingCiv
                val knowsFounder = civ.knows(founder) || founder == civ
                put("foundingCiv", if (knowsFounder) founder.civName else "???")
                if (knowsFounder) putJsonObject("founder") { colours(founder) }
                if (religion.isMajorReligion()) {
                    val holyCity = game.getCities().firstOrNull { it.isHolyCityOf(religion.name) }
                    if (holyCity != null) put("holyCity", if (civ.hasExplored(holyCity.getCenterTile())) holyCity.name else "???")
                }
                put("cities", founder.religionManager.numberOfCitiesFollowingThisReligion())
                put("followers", founder.religionManager.numberOfFollowersFollowingThisReligion("in all cities"))
                putJsonArray("beliefs") {
                    for (belief in religion.getAllBeliefsOrdered()) addJsonObject {
                        put("name", belief.name)
                        put("type", belief.type.name)
                        put("text", belief.getCivilopediaTextLines(withHeader = false).joinToString("\n") { it.text }.trim())
                    }
                }
            }
        }
    }

    // region wonders

    private fun JsonObjectBuilder.wonders(civ: Civilization) {
        val infos = try { WonderInfo().collectInfo(civ).toList() } catch (ex: Exception) {
            throw BridgeException("Couldn't list wonders: $ex")
        }
        putJsonArray("wonders") {
            for (w in infos) {
                if (w.status == WonderInfo.WonderStatus.Hidden) continue
                addJsonObject {
                    put("name", w.getNameColumn())
                    put("realName", w.name)
                    put("category", w.category.name)
                    put("group", w.groupName)
                    put("groupColor", w.groupColor.toString())
                    put("status", w.status.name)
                    put("statusText", w.getStatusColumn())
                    put("location", w.getLocationColumn())
                    val location = w.location
                    if (location != null && w.getLocationColumn().isNotEmpty()) { put("x", location.position.x); put("y", location.position.y) }
                }
            }
        }
    }

    // region notifications

    private fun JsonObjectBuilder.notifications(game: GameInfo, civ: Civilization) {
        putJsonArray("turns") {
            addJsonObject {
                put("turn", game.turns)
                put("year", game.getYear())
                put("current", true)
                putJsonArray("notifications") { civ.notifications.forEach { add(notificationJson(it)) } }
            }
            for (log in civ.notificationsLog.asReversed()) addJsonObject {
                put("turn", log.turn)
                put("year", game.getYear(log.turn - game.turns))
                put("current", false)
                putJsonArray("notifications") { log.notifications.forEach { add(notificationJson(it)) } }
            }
        }
    }

    private fun notificationJson(notification: Notification): JsonObject = buildJsonObject {
        put("text", notification.text)
        put("category", notification.category.name)
        putJsonArray("icons") { notification.icons.forEach { add(it) } }
        putJsonArray("actions") { notification.actions.forEach { add(actionJson(it)) } }
    }

    /** A notification's click action: its kind (Location, City, Tech, Diplomacy, Policy...) and its target. */
    private fun actionJson(action: NotificationAction): JsonObject = buildJsonObject {
        put("type", action.javaClass.simpleName.removeSuffix("Action"))
        for (field in action.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            val value = try { field.isAccessible = true; field.get(action) } catch (_: Exception) { null }
            when (value) {
                is HexCoord -> { put("x", value.x); put("y", value.y) }
                is String -> put(field.name, value)
                is Int -> put(field.name, value)
                is Boolean -> put(field.name, value)
            }
        }
    }
}
