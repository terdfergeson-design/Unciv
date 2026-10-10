package com.unciv.bridge

import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.CityStateFunctions
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The rest of Unciv's diplomacy screen: city-state gifts, tribute and protection, quests, denouncing, friendship,
 * demands, diplomatic marriage - and trades the way Unciv does them (the other civ answers your proposal with
 * yes, no, or a counteroffer).
 */
object DiplomacyActions {

    /** Extra fields for one civ in getDiplomacy: what you can do with them, and what's going on between you. */
    fun details(builder: JsonObjectBuilder, viewer: Civilization, other: Civilization) = with(builder) {
        val ours = viewer.getDiplomacyManager(other) ?: return@with
        val theirs = other.getDiplomacyManager(viewer) ?: return@with
        val yourTurn = viewer.isCurrentPlayer()
        val atWar = viewer.isAtWarWith(other)
        val relationsCanChange = !viewer.gameInfo.ruleset.modOptions.hasUnique(UniqueType.DiplomaticRelationshipsCannotChange)
        put("hello", if (other.isCityState) "" else if (theirs.isRelationshipLevelLE(RelationshipLevel.Enemy)) other.nation.hateHello else other.nation.neutralHello)
        put("relationshipLevel", theirs.relationshipLevel().name)
        put("relationsCanChange", relationsCanChange)
        if (theirs.hasFlag(DiplomacyFlags.DeclaredWar)) put("peaceBlockedTurns", theirs.getFlag(DiplomacyFlags.DeclaredWar))
        val capital = other.getCapital()
        if (capital != null && viewer.hasExplored(capital.getCenterTile())) putJsonObject("capital") {
            put("x", capital.location.x); put("y", capital.location.y)
        }

        if (!other.isCityState) {
            val friends = ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship)
            val denounced = ours.hasFlag(DiplomacyFlags.Denunciation)
            put("friends", friends)
            put("denounced", denounced)
            put("canTrade", !atWar && yourTurn)
            put("canOfferFriendship", !atWar && yourTurn && !friends
                && other.popupAlerts.none { it.type == AlertType.DeclarationOfFriendship && it.value == viewer.civID })
            put("friendshipOffered", other.popupAlerts.any { it.type == AlertType.DeclarationOfFriendship && it.value == viewer.civID })
            put("showFriendship", !atWar && !friends)
            put("canDenounce", !atWar && yourTurn && !denounced && !friends)
            put("showDenounce", !atWar && !denounced && !friends)
            putJsonArray("demands") {
                for (demand in Demand.entries) {
                    if (!demand.show(viewer)) continue
                    addJsonObject {
                        put("id", demand.name)
                        put("text", demand.demandText)
                        val already = other.popupAlerts.any { it.type == demand.demandAlert && it.value == viewer.civID }
                        val agreed = ours.hasFlag(demand.agreedToDemand)
                        put("enabled", yourTurn && !already && !agreed)
                        if (already) put("status", "asked") else if (agreed) put("status", "agreed")
                    }
                }
            }
            putJsonArray("promises") {
                for (demand in Demand.entries) {
                    if (theirs.hasFlag(demand.agreedToDemand))
                        add(demand.wePromisedText.replace("[turns]", theirs.getFlag(demand.agreedToDemand).toString()))
                    if (ours.hasFlag(demand.agreedToDemand))
                        add(demand.theyPromisedText.replace("[turns]", ours.getFlag(demand.agreedToDemand).toString()))
                }
            }
            if (!other.isHuman()) putJsonArray("modifiers") {
                for ((key, value) in theirs.diplomaticModifiers) {
                    if (key == DiplomaticModifiers.AttackedProtectedMinor.name && theirs.hasModifier(DiplomaticModifiers.DestroyedProtectedMinor)) continue
                    val modifier = DiplomaticModifiers.safeValueOf(key) ?: continue
                    addJsonObject { put("text", modifier.text); put("value", value.toInt()) }
                }
            }
            return@with
        }

        // City-states
        val functions = other.cityStateFunctions
        functions.updateAllyCivForCityState()
        val influence = theirs.getInfluence().toInt()
        put("isAlly", other.allyCiv == viewer)
        other.allyCiv?.let { ally ->
            put("allyName", if (viewer.knows(ally) || ally == viewer) ally.civName else "Unknown civilization")
            put("allyInfluence", other.getDiplomacyManager(ally)!!.getInfluence().toInt())
        }
        putJsonArray("protectors") {
            for (protector in functions.getProtectorCivs())
                add(if (viewer.knows(protector) || protector == viewer) protector.civName else "Unknown civilization")
        }
        put("nextLevel", when {
            atWar -> ""
            influence < 30 -> "Reach 30 for friendship."
            other.allyCiv == viewer -> ""
            else -> "Reach highest influence above 60 for alliance."
        })
        val level = theirs.relationshipIgnoreAfraid()
        if (level >= RelationshipLevel.Friend) {
            val turns = theirs.getTurnsToRelationshipChange()
            if (turns != 0) put("relationshipChangeTurns", turns)
        }
        val context = GameContext(viewer)
        for ((key, bonusLevel) in listOf("friendBonuses" to RelationshipLevel.Friend, "allyBonuses" to RelationshipLevel.Ally)) {
            putJsonArray(key) {
                for (bonus in CityStateFunctions.getCityStateBonuses(other.cityStateType, bonusLevel).filterNot { it.isHiddenToUsers() })
                    addJsonObject {
                        put("text", Gameplay.plain(bonus.getDisplayText()))
                        put("active", level == bonusLevel && bonus.conditionalsApply(context))
                    }
            }
        }
        putJsonArray("resources") {
            for (supply in functions.getCityStateResourcesForAlly()) {
                if (supply.resource.resourceType == ResourceType.Bonus) continue
                addJsonObject { put("name", supply.resource.name); put("amount", supply.amount) }
            }
        }
        other.cityStateUniqueUnit?.let { unit ->
            val techs = viewer.gameInfo.ruleset.units[unit]?.requiredTechs()?.joinToString(" and ") ?: ""
            put("uniqueUnit", "${other.civName} is able to provide $unit once $techs ${if (techs.contains(" and ")) "are" else "is"} researched.")
        }
        // Gifts
        put("canGift", yourTurn && !atWar)
        putJsonArray("goldGifts") {
            for (amount in listOf(250, 500, 1000)) addJsonObject {
                put("gold", amount)
                put("influence", functions.influenceGainedByGift(viewer, amount))
                put("enabled", yourTurn && !atWar && viewer.gold >= amount)
            }
        }
        putJsonArray("improvementGifts") {
            if (influence >= 60 && yourTurn) for ((tile, improvement) in improvableTiles(other)) addJsonObject {
                put("x", tile.position.x)
                put("y", tile.position.y)
                put("improvement", improvement)
                put("resource", tile.resource ?: "")
                put("enabled", viewer.gold >= 200)
            }
        }
        // Protection
        val protector = theirs.diplomaticStatus == DiplomaticStatus.Protector
        put("isProtector", protector)
        put("canPledge", yourTurn && !protector && functions.otherCivCanPledgeProtection(viewer))
        put("canRevoke", yourTurn && protector && functions.otherCivCanWithdrawProtection(viewer))
        // Tribute
        putJsonObject("tribute") {
            put("enabled", yourTurn && !atWar)
            put("gold", functions.goldGainedByTribute())
            put("canTakeGold", yourTurn && !atWar && functions.getTributeWillingness(viewer, demandingWorker = false) >= 0)
            put("canTakeWorker", yourTurn && !atWar && functions.getTributeWillingness(viewer, demandingWorker = true) >= 0)
            putJsonArray("modifiers") {
                val modifiers = functions.getTributeModifiers(viewer, requireWholeList = true)
                for ((text, value) in modifiers) addJsonObject { put("text", Gameplay.plain(text)); put("value", value) }
                addJsonObject { put("text", "Sum:"); put("value", modifiers.values.sum()) }
            }
        }
        // Diplomatic marriage (Austria)
        if (viewer.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) putJsonObject("marriage") {
            put("cost", functions.getDiplomaticMarriageCost())
            put("enabled", yourTurn && functions.canBeMarriedBy(viewer))
        }
        // Peace with a city-state: not while at war with its ally, nor soon after declaring war
        if (atWar) {
            val atWarWithAlly = other.allyCiv?.let { viewer.isAtWarWith(it) } == true
            put("canMakePeace", yourTurn && !atWarWithAlly && !theirs.hasFlag(DiplomacyFlags.DeclaredWar) && relationsCanChange)
        }
        // Quests
        putJsonArray("quests") {
            for (quest in other.questManager.getAssignedQuestsFor(viewer)) addJsonObject {
                put("name", Gameplay.plain(quest.questName))
                put("influence", quest.getInfluence().toInt())
                put("description", Gameplay.plain(quest.getDescription()))
                if (quest.doesExpire()) put("turns", quest.getRemainingTurns())
                if (quest.isGlobal()) {
                    val leader = try { other.questManager.getScoreStringForGlobalQuest(quest) } catch (_: Exception) { "" }
                    if (leader.isNotEmpty()) put("leader", leader)
                }
            }
            for (target in other.getKnownCivs().filter { other.questManager.isWarWithMajorActive(it) && it != viewer }) addJsonObject {
                put("name", "War against ${target.civName}")
                put("description", "We need you to help us defend against ${target.civName}. Killing ${other.questManager.unitsToKill(target)} of their military units would slow their offensive.")
                put("progress", if (viewer.knows(target)) "Currently you have killed ${other.questManager.unitsKilledSoFar(target, viewer)} of their military units." else "You need to find them first!")
            }
        }
    }

    /** City-state tiles with a luxury or strategic resource that could be improved (Unciv's "Gift Improvement"). */
    private fun improvableTiles(cityState: Civilization): List<Pair<com.unciv.logic.map.tile.Tile, String>> {
        val result = ArrayList<Pair<com.unciv.logic.map.tile.Tile, String>>()
        val improvements = cityState.gameInfo.ruleset.tileImprovements.values.filter { it.turnsToBuild != -1 }
        for (tile in cityState.cities.flatMap { it.getTiles() }) {
            val resource = tile.tileResource ?: continue
            if (!cityState.canSeeResource(resource) || resource.resourceType == ResourceType.Bonus) continue
            if (tile.improvement != null && resource.isImprovedBy(tile.improvement!!)) continue
            for (improvement in improvements)
                if (resource.isImprovedBy(improvement.name) && tile.improvementFunctions.canBuildImprovement(improvement, cityState.state))
                    result.add(tile to improvement.name)
        }
        return result
    }

    private fun requireTurn(viewer: Civilization) {
        if (!viewer.isCurrentPlayer()) throw BridgeException("It isn't your turn")
    }

    private fun cityState(viewer: Civilization, other: Civilization): Civilization {
        if (!other.isCityState) throw BridgeException("${other.civName} isn't a city-state")
        return other
    }

    /**
     * params: civ, action, and per action: gold (giftGold: 250 / 500 / 1000), x, y, improvement (giftImprovement),
     * demand (makeDemand: a Demand name), kind ("gold" or "worker" for tribute).
     */
    fun act(viewer: Civilization, other: Civilization, action: String, params: JsonObject): String {
        requireTurn(viewer)
        val ours = viewer.getDiplomacyManager(other) ?: throw BridgeException("You haven't met ${other.civName}")
        return when (action) {
            "giftGold" -> {
                cityState(viewer, other)
                val amount = params.requireInt("gold")
                if (amount !in listOf(250, 500, 1000)) throw BridgeException("Gifts are 250, 500 or 1000 gold")
                if (viewer.gold < amount) throw BridgeException("You don't have $amount gold")
                if (viewer.isAtWarWith(other)) throw BridgeException("No gifts while at war")
                val influence = other.cityStateFunctions.influenceGainedByGift(viewer, amount)
                other.cityStateFunctions.receiveGoldGift(viewer, amount)
                "${other.civName} thanks you for the gift (+$influence influence)."
            }
            "giftImprovement" -> {
                cityState(viewer, other)
                if (viewer.gold < 200) throw BridgeException("Improvements cost 200 gold")
                if (other.getDiplomacyManager(viewer)!!.getInfluence() < 60) throw BridgeException("You need 60 influence with ${other.civName}")
                val position = HexCoord(params.requireInt("x"), params.requireInt("y"))
                val name = params.requireStr("improvement")
                val (tile, _) = improvableTiles(other).firstOrNull { it.first.position == position && it.second == name }
                    ?: throw BridgeException("$name can't be built there")
                val improvement = viewer.gameInfo.ruleset.tileImprovements[name]!!
                viewer.addGold(-200)
                tile.stopWorkingOnImprovement()
                tile.setImprovement(improvement, other)
                other.cache.updateCivResources()
                "Built a $name for ${other.civName}."
            }
            "pledgeProtection" -> {
                cityState(viewer, other)
                if (!other.cityStateFunctions.otherCivCanPledgeProtection(viewer)) throw BridgeException("You can't pledge to protect ${other.civName} now")
                other.cityStateFunctions.addProtectorCiv(viewer)
                "You now protect ${other.civName}."
            }
            "revokeProtection" -> {
                cityState(viewer, other)
                if (!other.cityStateFunctions.otherCivCanWithdrawProtection(viewer)) throw BridgeException("You can't withdraw protection yet")
                other.cityStateFunctions.removeProtectorCiv(viewer)
                "You no longer protect ${other.civName}."
            }
            "tribute" -> {
                cityState(viewer, other)
                if (viewer.isAtWarWith(other)) throw BridgeException("Not while at war")
                val worker = params.str("kind") == "worker"
                if (other.cityStateFunctions.getTributeWillingness(viewer, demandingWorker = worker) < 0)
                    throw BridgeException("${other.civName} won't give in to that")
                if (worker) other.cityStateFunctions.tributeWorker(viewer) else {
                    val gold = other.cityStateFunctions.goldGainedByTribute()
                    other.cityStateFunctions.tributeGold(viewer)
                    return "${other.civName} pays $gold gold."
                }
                "${other.civName} hands over a worker."
            }
            "marriage" -> {
                cityState(viewer, other)
                if (!viewer.hasUnique(UniqueType.CityStateCanBeBoughtForGold) || !other.cityStateFunctions.canBeMarriedBy(viewer))
                    throw BridgeException("You can't marry into ${other.civName}")
                val cities = other.cities.toList()
                other.cityStateFunctions.diplomaticMarriage(viewer)
                for (city in cities) viewer.popupAlerts.add(PopupAlert(AlertType.DiplomaticMarriage, city.id))
                "${other.civName} joins your empire."
            }
            "denounce" -> {
                if (other.isCityState) throw BridgeException("City-states can't be denounced")
                if (viewer.isAtWarWith(other) || ours.hasFlag(DiplomacyFlags.Denunciation) || ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
                    throw BridgeException("You can't denounce ${other.civName} now")
                ours.denounce()
                other.nation.denounced.ifEmpty { "We will remember this." }
            }
            "offerFriendship" -> {
                if (other.isCityState) throw BridgeException("City-states don't sign declarations of friendship")
                if (viewer.isAtWarWith(other) || ours.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) throw BridgeException("You can't offer friendship now")
                if (other.popupAlerts.none { it.type == AlertType.DeclarationOfFriendship && it.value == viewer.civID })
                    other.popupAlerts.add(PopupAlert(AlertType.DeclarationOfFriendship, viewer.civID))
                "Your offer of friendship has been sent - ${other.civName} will answer on their turn."
            }
            "makeDemand" -> {
                if (other.isCityState) throw BridgeException("Make demands of city-states with tribute")
                val demand = Demand.entries.firstOrNull { it.name == params.requireStr("demand") && it.show(viewer) } ?: throw BridgeException("Unknown demand")
                if (ours.hasFlag(demand.agreedToDemand)) throw BridgeException("They already agreed to that")
                if (other.popupAlerts.none { it.type == demand.demandAlert && it.value == viewer.civID })
                    other.popupAlerts.add(PopupAlert(demand.demandAlert, viewer.civID))
                "Your demand has been sent - ${other.civName} will answer on their turn."
            }
            else -> throw BridgeException("Unknown diplomacy action '$action'")
        }
    }

    /**
     * Proposes the trade on [logic]'s table to [other] the way Unciv's diplomacy screen does, and lets their AI
     * answer straight away (Unciv lets them answer on their own turn): accepted, declined, or a counteroffer -
     * which comes back as a trade request from them (see [Alerts]).
     */
    fun propose(viewer: Civilization, other: Civilization, logic: TradeLogic): JsonObject {
        val before = viewer.tradeRequests.filter { it.requestingCiv == other.civID }.toSet()
        val notesBefore = viewer.notifications.size
        other.tradeRequests.removeAll { it.requestingCiv == viewer.civID }
        other.tradeRequests.add(TradeRequest(viewer.civID, logic.currentTrade.reverse()))
        viewer.cache.updateCivResources()
        TradeAutomation.respondToTradeRequests(other, true)
        val answer = viewer.notifications.drop(notesBefore).map { it.text }
        val counter = viewer.tradeRequests.firstOrNull { it.requestingCiv == other.civID && it !in before }
        val accepted = answer.any { it.contains("has accepted your trade request") }
        return buildJsonObject {
            put("accepted", accepted)
            put("counteroffer", counter != null)
            put("message", when {
                accepted -> "${other.civName} accepts the deal."
                counter != null -> "${other.civName} makes a counteroffer."
                else -> "${other.civName} turns the deal down."
            })
            if (counter != null) {
                put("counterKey", "TradeRequest|${other.civID}")
                putJsonArray("theyGive") { for (offer in counter.trade.theirOffers) addJsonObject { StateWriter.writeOffer(this, offer, viewer.gameInfo) } }
                putJsonArray("youGive") { for (offer in counter.trade.ourOffers) addJsonObject { StateWriter.writeOffer(this, offer, viewer.gameInfo) } }
            }
            put("diplomacy", StateWriter.diplomacy(viewer))
        }
    }
}
