package com.unciv.bridge

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.battle.BattleUnitCapture
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Unciv's popups for the player (AlertPopup, TradePopup, the World Congress results): what other civs say to you,
 * what you have to decide (a conquered city, a demand, a trade offer...) and things worth celebrating.
 *
 * Each alert has a key ("type|value", stable while it's pending), a title and text, the other civ when there is
 * one, and options [{id, label, description, enabled}]; [answer] runs the option the player picked, doing what
 * Unciv's popup button does.
 */
object Alerts {
    private class Option(val id: String, val label: String, val description: String = "", val enabled: Boolean = true)

    private class Alert(
        val key: String,
        val type: String,
        val title: String,
        val text: String,
        val options: List<Option>,
        /** Has to be answered before the turn can end (Unciv's popup is modal; these can't simply be closed) */
        val needsAnswer: Boolean,
        val civ: Civilization? = null,
        /** [text] is translated already (it has text Unciv translates itself in it) */
        val textTranslated: Boolean = false,
        val extra: JsonObjectBuilder.() -> Unit = {},
    )

    private fun close(label: String = "Close") = Option("close", label)

    /** Everything pending for [viewer], oldest first. */
    fun list(game: GameInfo, viewer: Civilization): List<JsonObject> {
        cleanUp(game, viewer)
        return pending(game, viewer).map { alert ->
            buildJsonObject {
                put("key", alert.key)
                put("type", alert.type)
                // Translated once, here: the texts above are written the way Unciv's translation files have them
                put("title", Lang.t(alert.title))
                put("text", if (alert.textTranslated) alert.text else Lang.t(alert.text))
                put("needsAnswer", alert.needsAnswer)
                alert.civ?.let { civ ->
                    put("civ", civ.civName)
                    put("nation", civ.nation.name)
                    put("leader", try { civ.getLeaderDisplayName() } catch (_: Exception) { civ.nation.leaderName })
                    put("isCityState", civ.isCityState)
                }
                putJsonArray("options") {
                    for (option in alert.options) addJsonObject {
                        put("id", option.id)
                        put("label", Lang.t(option.label))
                        if (option.description.isNotEmpty()) put("description", Lang.t(option.description))
                        put("enabled", option.enabled)
                    }
                }
                alert.extra(this)
            }
        }
    }

    /** How many pending alerts need a decision (these block the end of the turn). */
    fun countNeedingAnswer(game: GameInfo, viewer: Civilization): Int {
        cleanUp(game, viewer)
        return pending(game, viewer).count { it.needsAnswer }
    }

    /** Ending the turn: messages that only had a "close" button are taken as read. */
    fun dismissInformational(game: GameInfo, viewer: Civilization) {
        val keep = pending(game, viewer).filter { it.needsAnswer }.map { it.key }.toSet()
        viewer.popupAlerts.removeAll { alertKey(it) !in keep }
        if (viewer.shouldShowDiplomaticVotingResults()) Gameplay.dismissVoteResults(viewer)
    }

    private fun alertKey(alert: PopupAlert) = "${alert.type.name}|${alert.value}"

    /** Drops alerts that no longer make sense (the other civ is gone, the trade can't happen any more...). */
    private fun cleanUp(game: GameInfo, viewer: Civilization) {
        for (request in viewer.tradeRequests.toList()) {
            val other = game.civilizations.firstOrNull { it.civID == request.requestingCiv }
            if (other == null || other.isDefeated() || !TradeEvaluation().isTradeValid(request.trade, viewer, other))
                viewer.tradeRequests.remove(request)
        }
    }

    private fun pending(game: GameInfo, viewer: Civilization): List<Alert> {
        val result = ArrayList<Alert>()
        for (popup in viewer.popupAlerts.toList()) {
            val alert = try { describe(game, viewer, popup) } catch (ex: Exception) {
                System.err.println("Skipping alert ${popup.type} ${popup.value}: $ex")
                null
            }
            if (alert != null) result.add(alert)
        }
        Gameplay.voteResults(viewer)?.let { results ->
            result.add(Alert("VoteResults|", "VoteResults", "World Congress", results["summary"].toString().trim('"'),
                listOf(close("Continue")), needsAnswer = false) { put("votes", results) })
        }
        for (request in viewer.tradeRequests) {
            val other = game.civilizations.firstOrNull { it.civID == request.requestingCiv } ?: continue
            result.add(Alert("TradeRequest|${other.civID}", "TradeRequest", "[${other.civName}]'s trade offer",
                other.nation.tradeRequest.ifEmpty { "Would you be interested in this deal?" },
                listOf(Option("accept", "Sounds good!"), Option("decline", "Not this time."),
                    Option("counter", "How about something else...")),
                needsAnswer = true, civ = other) {
                // From our side: theirOffers is what they give us, ourOffers what we'd give
                putJsonArray("theyGive") { for (offer in request.trade.theirOffers) addJsonObject { StateWriter.writeOffer(this, offer, game) } }
                putJsonArray("youGive") { for (offer in request.trade.ourOffers) addJsonObject { StateWriter.writeOffer(this, offer, game) } }
            })
        }
        return result
    }

    private fun civ(game: GameInfo, id: String): Civilization? = game.civilizations.firstOrNull { it.civID == id || it.civName == id }
    private fun city(game: GameInfo, id: String): City? = game.getCities().firstOrNull { it.id == id }

    private fun describe(game: GameInfo, viewer: Civilization, popup: PopupAlert): Alert? {
        val key = alertKey(popup)
        val type = popup.type.name
        fun info(title: String, text: String, civ: Civilization? = null, button: String = "Close", extra: JsonObjectBuilder.() -> Unit = {}) =
            Alert(key, type, title, text, listOf(close(button)), needsAnswer = false, civ = civ, extra = extra)
        val value = popup.value
        return when (popup.type) {
            AlertType.CityConquered -> {
                val city = city(game, value) ?: return null
                Alert(key, type, "What would you like to do with the city of [${city.name}]?", "", conquestOptions(city, viewer),
                    needsAnswer = true) { put("city", city.name); put("x", city.location.x); put("y", city.location.y) }
            }
            AlertType.CityTraded -> {
                val city = city(game, value) ?: return null
                val founder = city.foundingCivObject
                val options = ArrayList<Option>()
                if (founder != null && !viewer.isAtWarWith(founder))
                    options.add(Option("liberate", "Liberate (city returns to [${founder.civName}])",
                        "Liberating a city returns it to its original owner, giving you a massive relationship boost with them!"))
                options.add(Option("keep", "Keep it"))
                Alert(key, type, "What would you like to do with the city of [${city.name}]?", "", options, needsAnswer = true) {
                    put("city", city.name); put("x", city.location.x); put("y", city.location.y)
                }
            }
            AlertType.DiplomaticMarriage -> {
                val city = city(game, value) ?: return null
                val mayAnnex = !viewer.hasUnique(UniqueType.MayNotAnnexCities)
                val options = if (viewer.isOneCityChallenger()) listOf(Option("destroy", "Destroy", "Destroying the city instantly razes the city to the ground."))
                    else listOf(annexOption(mayAnnex), puppetOption(mayAnnex))
                Alert(key, type, "{${city.name}}: {What would you like to do with the city?}", "", options, needsAnswer = true) {
                    put("city", city.name); put("x", city.location.x); put("y", city.location.y)
                }
            }
            AlertType.FirstContact -> {
                val other = civ(game, value) ?: return null
                if (other.isCityState) info("First contact", "We have encountered the City-State of [${other.nation.name}]!", other, "Excellent!")
                else info("First contact", other.nation.introduction, other, "A pleasure to meet you.")
            }
            AlertType.WarDeclaration -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                Alert(key, type, "DECLARATION OF WAR", other.nation.declaringWar,
                    listOf(close("You'll pay for this!"), close("Very well.")), needsAnswer = false, civ = other)
            }
            AlertType.BorderConflict -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                Alert(key, type, other.civName, "Remove your troops in our border immediately!",
                    listOf(close("Sorry."), close("Never!")), needsAnswer = false, civ = other)
            }
            AlertType.TilesStolen -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                info(other.civName, "Those lands were not yours to take. This has not gone unnoticed.", other)
            }
            AlertType.Denounced -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                val friendly = other.getDiplomacyManager(viewer)!!.isRelationshipLevelGE(RelationshipLevel.Competitor)
                val text = if (friendly) other.nation.neutralDenouncing.ifEmpty { "You have violated our bond of trust. This is intolerable!" }
                    else other.nation.hateDenouncing.ifEmpty { "You are a scourge upon this earth. I denounce you!" }
                val options = ArrayList<Option>()
                if (viewer.getDiplomacyManager(other)!!.canDeclareWar()) options.add(Option("war", "THIS MEANS WAR! (Declare war)"))
                options.add(close("Very well."))
                Alert(key, type, "DENOUNCEMENT", text, options, needsAnswer = false, civ = other)
            }
            AlertType.DemandToStopSettlingCitiesNear, AlertType.DemandToStopSpreadingReligion,
            AlertType.DemandToStopSpyingOnUs, AlertType.DemandToNotAttackUs -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                val demand = Demand.entries.first { it.demandAlert == popup.type }
                Alert(key, type, other.civName, demand.demandText,
                    listOf(Option("accept", demand.acceptDemandText), Option("refuse", demand.refuseDemandText)),
                    needsAnswer = true, civ = other)
            }
            AlertType.CitySettledNearOtherCivDespiteOurPromise, AlertType.ReligionSpreadDespiteOurPromise,
            AlertType.AttackedUsDespitePromise -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                val demand = Demand.entries.first { it.violationDiscoveredAlert == popup.type }
                info(other.civName, demand.violationNoticedText, other, "Very well.")
            }
            AlertType.SpyingOnUsDespiteOurPromise -> {
                // Unciv shows this one as the demand itself again
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                val demand = Demand.DontSpyOnUs
                Alert(key, type, other.civName, demand.demandText,
                    listOf(Option("accept", demand.acceptDemandText), Option("refuse", demand.refuseDemandText)),
                    needsAnswer = true, civ = other)
            }
            AlertType.AcceptingDemand -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                info("ACCEPTING DEMAND", other.nation.acceptingDemand.ifEmpty { "We will comply, but our consent is given grudgingly." }, other, "Very well.")
            }
            AlertType.RejectingDemand -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated()) return null
                val friendly = other.getDiplomacyManager(viewer)!!.isRelationshipLevelGE(RelationshipLevel.Competitor)
                val text = if (friendly) other.nation.neutralRejectingDemand.ifEmpty { "Your demands are in poor taste. We shall decide this matter on our own." }
                    else other.nation.hateRejectingDemand.ifEmpty { "Did you really expect us to bend to such brazen demands?" }
                Alert(key, type, "REJECTING DEMAND", text, listOf(close("You'll pay for this!"), close("Very well.")), needsAnswer = false, civ = other)
            }
            AlertType.DeclarationOfFriendship -> {
                val other = civ(game, value) ?: return null
                if (other.isDefeated() || other.getDiplomacyManager(viewer)!!.diplomaticStatus == DiplomaticStatus.War) return null
                Alert(key, type, "DECLARATION OF FRIENDSHIP",
                    other.nation.declaringFriendship.ifEmpty { "My friend, shall we declare our friendship to the world?" },
                    listOf(Option("accept", "Declare Friendship ([30] turns)"), Option("decline", "We are not interested.")),
                    needsAnswer = true, civ = other)
            }
            AlertType.BulliedProtectedMinor, AlertType.AttackedProtectedMinor, AlertType.AttackedAllyMinor -> {
                val parts = value.split('@')
                val bully = civ(game, parts[0]) ?: return null
                if (bully.isDefeated()) return null
                val cityState = civ(game, parts.getOrElse(1) { "" })?.civName ?: "a city-state"
                val nice = bully.getDiplomacyManager(viewer)!!.isRelationshipLevelGE(RelationshipLevel.Neutral)
                val text = when {
                    popup.type == AlertType.BulliedProtectedMinor && nice ->
                        "I've been informed that my armies have taken tribute from [$cityState], a city-state under your protection.\nI assure you, this was quite unintentional, and I hope that this does not serve to drive us apart."
                    popup.type == AlertType.BulliedProtectedMinor ->
                        "We asked [$cityState] for a tribute recently and they gave in.\nYou promised to protect them from such things, but we both know you cannot back that up."
                    nice -> "It's come to my attention that I may have attacked [$cityState].\nWhile it was not my goal to be at odds with your empire, this was deemed a necessary course of action."
                    else -> "I thought you might like to know that I've launched an invasion of one of your little pet states.\nThe lands of [$cityState] will make a fine addition to my own."
                }
                Alert(key, type, bully.civName, text, listOf(close("You'll pay for this!"), close("Very well.")), needsAnswer = false, civ = bully)
            }
            AlertType.Defeated -> {
                val other = civ(game, value) ?: return null
                info("{${other.civName}}: {Defeated}", other.nation.defeated, other, "Farewell.")
            }
            AlertType.WonderBuilt -> {
                val wonder = game.ruleset.buildings[value] ?: return null
                // The short description comes translated from Unciv; the quote is translated here
                Alert(key, type, wonder.name, listOf(Lang.t(wonder.quote), try { wonder.getShortDescription() } catch (_: Exception) { "" })
                    .filter { it.isNotBlank() }.joinToString("\n\n"), listOf(close()), needsAnswer = false,
                    extra = { put("wonder", wonder.name) }, textTranslated = true)
            }
            AlertType.TechResearched -> {
                val tech = game.ruleset.technologies[value] ?: return null
                info(tech.name, tech.quote) { put("tech", tech.name) }
            }
            AlertType.GoldenAge -> info("GOLDEN AGE", "Your citizens have been happy with your rule for so long that the empire enters a Golden Age!")
            AlertType.StartIntro -> info(viewer.civName, listOf(viewer.nation.startIntroPart1, viewer.nation.startIntroPart2)
                .filter { it.isNotBlank() }.joinToString("\n\n") { "{$it}" }, viewer, "Let's begin!")
            AlertType.RecapturedCivilian -> {
                val tile = game.tileMap[HexCoord.fromString(value)]
                val unit = tile.civilianUnit ?: return null
                val owner = unit.originalOwningCiv ?: return null
                if (owner.isDefeated()) return null
                Alert(key, type, "Return [${unit.name}] to [${owner.civName}]?",
                    "The [${unit.name}] we liberated originally belonged to [${owner.civName}]. They will be grateful if we return it to them.",
                    listOf(Option("yes", "Yes"), Option("no", "No")), needsAnswer = true, civ = owner) {
                    put("x", tile.position.x); put("y", tile.position.y)
                }
            }
            AlertType.GameHasBeenWon -> {
                val data = game.victoryData ?: return null
                val winner = game.civilizations.firstOrNull { it.civID == data.winningCiv }?.civName ?: data.winningCiv
                Alert(key, type, "Victory", "[$winner] has won a [${data.victoryType}] Victory!",
                    listOf(Option("victory", "Victory status"), close()), needsAnswer = false)
            }
            AlertType.Event -> {
                val parts = value.split(Constants.stringSplitCharacter)
                val event = game.ruleset.events[parts[0]] ?: return null
                val unitId = parts.drop(1).firstOrNull { it.startsWith("unitId=") }?.substringAfter("unitId=")?.toIntOrNull()
                val unit = unitId?.let { viewer.units.getUnitById(it) }
                val choices = event.getMatchingChoices(GameContext(viewer, unit = unit)) ?: return null
                val options = choices.mapIndexed { index, choice ->
                    Option("choice$index", choice.text, choice.uniqueObjects.filter { it.isTriggerable && !it.isHiddenToUsers() }
                        .joinToString("\n") { Gameplay.plain(it.getDisplayText()) })
                }.ifEmpty { listOf(close()) }
                Alert(key, type, event.name, event.text, options, needsAnswer = choices.isNotEmpty())
            }
        }
    }

    private fun annexOption(mayAnnex: Boolean) = Option("annex", "Annex",
        if (mayAnnex) Lang.sentences("Annexed cities become part of your regular empire.", "Their citizens generate 2x the unhappiness, unless you build a courthouse.")
        else "Your civilization may not annex this city.", enabled = mayAnnex)

    private fun puppetOption(mayAnnex: Boolean) = Option("puppet", "Puppet",
        Lang.sentences("Puppeted cities do not increase your tech or policy cost.", "You have no control over the production of puppeted cities.",
            "Puppeted cities also generate 25% less Science and Culture.", if (mayAnnex) "A puppeted city can be annexed at any time." else ""))

    private fun conquestOptions(city: City, viewer: Civilization): List<Option> {
        val options = ArrayList<Option>()
        val founder = city.foundingCivObject
        // Unciv asks while the city still belongs to the loser; here the battle code has moved it to us already,
        // so "the city belonged to someone other than its founder" is about its previous owner
        val ownerBefore = if (city.civ == viewer) city.previousOwner else city.civ.civID
        if (founder != null && founder != viewer && ownerBefore != founder.civID)
            options.add(Option("liberate", "Liberate (city returns to [${founder.civName}])",
                "Liberating a city returns it to its original owner, giving you a massive relationship boost with them!"))
        if (viewer.isOneCityChallenger()) {
            options.add(Option("destroy", "Destroy", "Destroying the city instantly razes the city to the ground."))
            return options
        }
        val mayAnnex = !viewer.hasUnique(UniqueType.MayNotAnnexCities)
        options.add(annexOption(mayAnnex))
        options.add(puppetOption(mayAnnex))
        val canRaze = city.canBeDestroyed(justCaptured = true)
        options.add(Option("raze", "Raze", when {
            !canRaze -> "Original capitals and holy cities cannot be razed."
            mayAnnex -> Lang.sentences("Razing the city annexes it, and starts burning the city to the ground.", "The population will gradually dwindle until the city is destroyed.")
            else -> Lang.sentences("Razing the city puppets it, and starts burning the city to the ground.", "The population will gradually dwindle until the city is destroyed.")
        }, enabled = canRaze))
        return options
    }

    /**
     * The player picked [optionId] in the alert [key]. Returns a short status line. For a trade request's
     * "counter" the result also carries the trade, so the client can open the trade screen with it.
     */
    fun answer(game: GameInfo, viewer: Civilization, key: String, optionId: String): JsonObject {
        if (key.startsWith("TradeRequest|")) return answerTrade(game, viewer, key.substringAfter('|'), optionId)
        if (key == "VoteResults|") {
            Gameplay.dismissVoteResults(viewer)
            return status("")
        }
        val popup = viewer.popupAlerts.firstOrNull { alertKey(it) == key } ?: throw BridgeException("That message is no longer there")
        val alert = describe(game, viewer, popup)
        if (alert == null) {
            viewer.popupAlerts.remove(popup)
            return status("")
        }
        val option = alert.options.firstOrNull { it.id == optionId } ?: throw BridgeException("No option '$optionId'")
        if (!option.enabled) throw BridgeException("${option.label} isn't possible")
        val value = popup.value
        var message = ""
        when (popup.type) {
            AlertType.CityConquered -> message = decideConqueredCity(city(game, value)!!, viewer, optionId)
            AlertType.CityTraded -> if (optionId == "liberate") {
                val city = city(game, value)!!
                city.liberateCity(viewer)
                message = "[${city.name}] returns to [${city.civ.civName}]."
            }
            AlertType.DiplomaticMarriage -> {
                val city = city(game, value)!!
                when (optionId) {
                    "destroy" -> city.destroyCity(overrideSafeties = true)
                    "annex" -> city.annexCity()
                    "puppet" -> { city.isPuppet = true; city.cityStats.update() }
                }
            }
            AlertType.Denounced -> if (optionId == "war") viewer.getDiplomacyManager(civ(game, value)!!)!!.declareWar()
            AlertType.DemandToStopSettlingCitiesNear, AlertType.DemandToStopSpreadingReligion,
            AlertType.DemandToStopSpyingOnUs, AlertType.DemandToNotAttackUs, AlertType.SpyingOnUsDespiteOurPromise -> {
                val other = civ(game, value)!!
                val demand = if (popup.type == AlertType.SpyingOnUsDespiteOurPromise) Demand.DontSpyOnUs
                    else Demand.entries.first { it.demandAlert == popup.type }
                val diplomacy = viewer.getDiplomacyManager(other)!!
                if (optionId == "accept") diplomacy.agreeToDemand(demand)
                else {
                    diplomacy.refuseDemand(demand)
                    if (demand == Demand.DoNotAttackUs) diplomacy.declareWar()
                }
            }
            AlertType.DeclarationOfFriendship -> {
                val other = civ(game, value)!!
                val diplomacy = viewer.getDiplomacyManager(other)!!
                if (optionId == "accept") { diplomacy.signDeclarationOfFriendship(); message = "You and [${other.civName}] are now friends." }
                else diplomacy.otherCivDiplomacy().setFlag(DiplomacyFlags.DeclinedDeclarationOfFriendship, 20)
            }
            AlertType.RecapturedCivilian -> message = returnCivilian(game, viewer, value, optionId == "yes")
            AlertType.Event -> if (optionId.startsWith("choice")) {
                val parts = value.split(Constants.stringSplitCharacter)
                val event = game.ruleset.events[parts[0]]!!
                val unitId = parts.drop(1).firstOrNull { it.startsWith("unitId=") }?.substringAfter("unitId=")?.toIntOrNull()
                val unit = unitId?.let { viewer.units.getUnitById(it) }
                val choices = event.getMatchingChoices(GameContext(viewer, unit = unit))?.toList() ?: emptyList()
                choices.getOrNull(optionId.removePrefix("choice").toInt())?.triggerChoice(viewer, unit)
            }
            else -> {}
        }
        viewer.popupAlerts.remove(popup)
        return status(message)
    }

    private fun status(message: String) = buildJsonObject { put("message", Lang.t(message)) }

    /**
     * Unciv asks the conqueror what to do with a city. Without Unciv's world screen its battle code has already made
     * the AI's choice (the city is our puppet - perhaps being razed - by now), so this changes it to the player's.
     */
    private fun decideConqueredCity(city: City, viewer: Civilization, choice: String): String {
        val mayAnnex = !viewer.hasUnique(UniqueType.MayNotAnnexCities)
        if (city.civ != viewer) {
            // Unciv's own order of events (the city is still the loser's): exactly what its popup does
            when (choice) {
                "liberate" -> city.liberateCity(viewer)
                "destroy" -> { city.puppetCity(viewer); city.destroyCity() }
                "annex" -> { city.puppetCity(viewer); city.annexCity() }
                "puppet" -> city.puppetCity(viewer)
                "raze" -> { city.puppetCity(viewer); if (mayAnnex) city.annexCity(); city.isBeingRazed = true }
            }
        } else when (choice) {
            "liberate" -> {
                // Liberating runs the capture steps again (lost buildings and citizens): undo that second round
                val population = city.population.population
                val buildings = city.cityConstructions.getBuiltBuildings().filter { !it.isNationalWonder }.map { it.name }
                city.liberateCity(viewer)
                for (name in buildings) if (!city.cityConstructions.isBuilt(name)) city.cityConstructions.addBuilding(name)
                if (city.population.population < population) city.population.setPopulation(population)
                city.cityStats.update()
                return "[${city.name}] returns to [${city.civ.civName}]."
            }
            "destroy" -> city.destroyCity(overrideSafeties = true)
            "annex" -> { city.isBeingRazed = false; city.annexCity() }
            "puppet" -> {
                city.isBeingRazed = false
                if (!city.isPuppet) {
                    city.isPuppet = true
                    city.cityConstructions.removeAll()
                    city.cityStats.update()
                }
            }
            "raze" -> {
                if (mayAnnex && city.isPuppet) city.annexCity()
                city.isBeingRazed = true
            }
        }
        return when (choice) {
            "annex" -> "[${city.name}] is now part of your empire."
            "puppet" -> "[${city.name}] is now a puppet."
            "raze" -> "[${city.name}] is being razed."
            "destroy" -> "[${city.name}] was destroyed."
            else -> ""
        }
    }

    private fun returnCivilian(game: GameInfo, captor: Civilization, value: String, giveBack: Boolean): String {
        val tile = game.tileMap[HexCoord.fromString(value)]
        val unit = tile.civilianUnit ?: return ""
        val owner = unit.originalOwningCiv ?: return ""
        if (!giveBack) {
            BattleUnitCapture.captureOrConvertToWorker(unit, captor)
            return ""
        }
        val unitName = unit.baseUnit.name
        unit.destroy()
        val closestCity = owner.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        if (closestCity != null) owner.units.placeUnitNearTile(closestCity.location.toHexCoord(), unitName)
        if (owner.isCityState) owner.getDiplomacyManagerOrMeet(captor).addInfluence(45f)
        else if (owner.isMajorCiv()) owner.getDiplomacyManagerOrMeet(captor).setModifier(DiplomaticModifiers.ReturnedCapturedUnits, 20f)
        owner.addNotification("Your captured [$unitName] has been returned by [${captor.civName}]", tile.position,
            NotificationCategory.Diplomacy, NotificationIcon.Trade, unitName, captor.civName)
        return "The [$unitName] goes back to [${owner.civName}]."
    }

    private fun answerTrade(game: GameInfo, viewer: Civilization, civId: String, optionId: String): JsonObject {
        val request = viewer.tradeRequests.firstOrNull { it.requestingCiv == civId } ?: throw BridgeException("That offer is no longer there")
        val other = game.civilizations.first { it.civID == civId }
        viewer.tradeRequests.remove(request)
        return when (optionId) {
            "accept" -> {
                val logic = TradeLogic(viewer, other)
                logic.currentTrade.set(request.trade)
                logic.acceptTrade()
                other.addNotification("[${viewer.civName}] has accepted your trade request", NotificationCategory.Trade, viewer.civName, NotificationIcon.Trade)
                status("Excellent! The deal with [${other.civName}] is done.")
            }
            "decline" -> {
                request.decline(viewer)
                other.addNotification("[${viewer.civName}] has denied your trade request", NotificationCategory.Trade, viewer.civName, NotificationIcon.Trade)
                status("")
            }
            "counter" -> buildJsonObject {
                // Unciv opens the diplomacy screen with their offer on the table, to change and propose back
                put("message", "")
                put("civ", other.civName)
                putJsonArray("ours") { for (offer in request.trade.ourOffers) addJsonObject { StateWriter.writeOffer(this, offer, game) } }
                putJsonArray("theirs") { for (offer in request.trade.theirOffers) addJsonObject { StateWriter.writeOffer(this, offer, game) } }
            }
            else -> throw BridgeException("No option '$optionId'")
        }
    }

    /** After the player's unit takes a city: Unciv's "what would you like to do with the city" question. */
    fun cityConquered(viewer: Civilization, city: City) {
        if (city.civ != viewer) return   // destroyed or liberated already (one-city challenge, barbarians...)
        if (city.isOriginalCapital && city.foundingCivObject == viewer) return   // retaking our old capital: annexed, no question
        val alert = PopupAlert(AlertType.CityConquered, city.id)
        if (viewer.popupAlerts.none { it.type == alert.type && it.value == alert.value }) viewer.popupAlerts.add(alert)
    }
}
