package com.unciv.bridge

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Milestone
import com.unciv.models.ruleset.MilestoneType
import com.unciv.models.ruleset.Victory
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.view.GameView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The parts of Unciv's world screen that aren't about the map: what has to be decided before the turn can end
 * (Unciv's next-turn button), the free great person picker, the World Congress vote, and the victory screen.
 */
object Gameplay {

    /**
     * What stops the turn from ending, in the order Unciv's next-turn button goes through them
     * (NextTurnAction): each {type, text, ...}. [alertsToAnswer] are popups that need a choice
     * (see [Alerts]); [checkDueUnits] is Unciv's "check for idle units" setting.
     */
    fun blockers(game: GameInfo, civ: Civilization, alertsToAnswer: Int, checkDueUnits: Boolean): List<JsonObject> {
        val list = ArrayList<JsonObject>()
        if (!civ.isCurrentPlayer() || civ.isDefeated()) return list
        val civView = GameView(game, civ).civView
        fun add(type: String, text: String, extra: JsonObjectBuilder.() -> Unit = {}) =
            list.add(buildJsonObject { put("type", type); put("text", Lang.t(text)); extra() })

        if (alertsToAnswer > 0) add("AnswerAlerts", if (alertsToAnswer == 1) "Answer the message" else "Answer [$alertsToAnswer] messages")
        if (civ.greatPeople.freeGreatPeople > 0 && civ.greatPeople.getFreeGreatPersonOptions().isNotEmpty()
            && greatPersonDeferredOnTurn != game.turns)
            add("PickGreatPerson", "Choose a free great person")
        civView.cities().firstOrNull { !it.isPuppet() && it.currentConstructionName().isEmpty() }?.let { city ->
            add("PickConstruction", "{Pick construction}: {${city.name}}") {
                put("city", city.name)
                put("x", city.location.x)
                put("y", city.location.y)
            }
        }
        if (civView.shouldOpenTechPicker()) add("PickTech", "Pick a tech")
        if (civView.shouldShowPolicyPicker()) add("PickPolicy", "Pick a policy")
        if (game.isEspionageEnabled() && civView.shouldShowMoveSpies()) add("MoveSpies", "Move Spies")
        when {
            civView.canFoundPantheon() -> add("FoundPantheon", "Found Pantheon")
            civView.canExpandPantheon() -> add("ExpandPantheon", "Expand Pantheon")
            civView.isFoundingReligion() -> add("FoundReligion", "Found Religion")
            civView.isEnhancingReligion() -> add("EnhanceReligion", "Enhance a Religion")
            civView.hasFreeBeliefs() -> add("ReformReligion", "Reform Religion")
        }
        if (civView.mayVoteForDiplomaticVictory()) add("WorldCongressVote", "Vote for World Leader")
        if (checkDueUnits) {
            val due = civView.dueUnitsCount()
            if (due > 0) add("NextUnit", if (due == 1) "1 unit needs orders" else "[$due] units need orders") {
                civ.units.getDueUnits().firstOrNull()?.let {
                    put("unitId", it.id)
                    put("x", it.getTile().position.x)
                    put("y", it.getTile().position.y)
                }
            }
        }
        return list
    }

    /** The policy and move-spies prompts only stop you once, like Unciv's (opening the screen dismisses them). */
    fun dismiss(civ: Civilization, type: String) {
        when (type) {
            "PickPolicy" -> civ.policies.shouldOpenPolicyPicker = false
            "MoveSpies" -> civ.espionageManager.dismissedShouldMoveSpies = true
            else -> throw BridgeException("'$type' can't be dismissed - it needs a choice")
        }
    }

    // region great people and the World Congress

    /** Unciv's free great person picker: every great person, and which you may pick now. */
    fun greatPersonChoices(civ: Civilization): JsonObject = buildJsonObject {
        val available = civ.greatPeople.getFreeGreatPersonOptions().map { it.name }.toSet()
        put("free", civ.greatPeople.freeGreatPeople)
        putJsonArray("choices") {
            for (unit in civ.greatPeople.getGreatPeople()) addJsonObject {
                put("name", unit.name)
                put("available", unit.name in available)
                put("description", try { unit.getShortDescription() } catch (_: Exception) { "" })
            }
        }
    }

    /** The turn a free great person found no space next to the capital: it waits for a later turn (see below). */
    private var greatPersonDeferredOnTurn = -1

    fun chooseGreatPerson(civ: Civilization, name: String) {
        if (civ.greatPeople.freeGreatPeople <= 0) throw BridgeException("You have no free great person to pick")
        if (civ.greatPeople.getFreeGreatPersonOptions().none { it.name == name }) throw BridgeException("$name isn't available")
        if (civ.greatPeople.chooseFreeGreatPerson(name) == null) {
            // Like Unciv's picker ("No space to place this unit", then it can be closed): the turn may end, and the
            // free great person is kept for a later turn - otherwise a capital hemmed in by units would block the
            // game for good
            greatPersonDeferredOnTurn = civ.gameInfo.turns
            throw BridgeException(Lang.t("No space to place this unit") + " - " +
                "move a unit away from your capital and pick again, or end the turn and pick later.")
        }
    }

    /** Who you can vote for as world leader (Unciv's DiplomaticVotePickerScreen). */
    fun voteChoices(civ: Civilization): JsonObject = buildJsonObject {
        put("canVote", civ.mayVoteForDiplomaticVictory())
        putJsonArray("civs") {
            for (other in civ.diplomacyFunctions.getKnownCivsSorted(false)) addJsonObject {
                put("name", other.civName)
                put("nation", other.nation.name)
            }
        }
    }

    /** params civ: who to vote for (a civ name), or null to abstain. */
    fun vote(civ: Civilization, choice: String?) {
        if (!civ.mayVoteForDiplomaticVictory()) throw BridgeException("There's no vote to cast right now")
        val target = choice?.let { name -> civ.gameInfo.civilizations.firstOrNull { it.civName == name } ?: throw BridgeException("Unknown civ $name") }
        civ.diplomaticVoteForCiv(target?.civID)
    }

    /** The result of the last World Congress vote (Unciv's DiplomaticVoteResultScreen), or null if there's nothing to show. */
    fun voteResults(civ: Civilization): JsonObject? {
        if (!civ.shouldShowDiplomaticVotingResults()) return null
        val game = civ.gameInfo
        val (unBuilding, unOwner) = civ.victoryManager.getUNBuildingAndOwnerNames()
        val breakdown = civ.victoryManager.getDiplomaticVictoryVoteBreakdown()
        return buildJsonObject {
            put("summary", breakdown.winnerText)   // translated where it's shown (Alerts)
            putJsonArray("civs") {
                for (other in game.getCivsSorted(civToSortFirst = civ)) addJsonObject {
                    put("name", other.civName)
                    put("nation", other.nation.name)
                    put("isMajor", other.isMajorCiv())
                    if (other.isMajorCiv()) put("votesReceived", breakdown.results[other.civID])
                    put("votes", if (other.civID == unOwner && unBuilding != null) 2 else 1)
                    val votedFor = game.diplomaticVictoryVotesCast[other.civID]?.let { id -> game.civilizations.firstOrNull { it.civID == id } }
                    if (votedFor != null && !votedFor.isDefeated()) put("votedFor", votedFor.civName) else put("abstained", true)
                }
            }
        }
    }

    fun dismissVoteResults(civ: Civilization) {
        civ.addFlag(CivFlags.ShowDiplomaticVotingResults.name, -1)
    }

    // endregion

    // region victory screen

    /**
     * Unciv's victory screen: our status (each victory's milestones with their steps), the global status (how
     * far each civ is), rankings, the charts' history, demographics, and who won (if anyone).
     */
    fun victory(game: GameInfo, viewer: Civilization): JsonObject = buildJsonObject {
        val victories = viewer.victoryManager.getVictoriesShownInVictoryScreen()
        val majors = game.civilizations.filter { it.isMajorCiv() }
        val params = game.gameParameters
        val gameOver = game.victoryData != null
        put("turn", game.turns)
        put("speed", params.speed)
        put("difficulty", game.difficulty)
        if ("Time" in params.victoryTypes) put("maxTurns", params.maxTurns)
        put("oneMoreTurnMode", game.oneMoreTurnMode)
        put("defeated", viewer.isDefeated())
        game.victoryData?.let { data ->
            val winner = game.civilizations.firstOrNull { it.civID == data.winningCiv }
            val victory = game.ruleset.victories[data.victoryType] ?: Victory()
            putJsonObject("winner") {
                put("civ", winner?.civName ?: data.winningCiv)
                put("victoryType", data.victoryType)
                put("turn", data.victoryTurn)
                put("isYou", winner == viewer)
                put("text", Lang.t(if (winner == viewer) victory.victoryString else victory.defeatString))
            }
        }
        if (game.victoryData == null && viewer.isDefeated()) put("defeatText", Lang.t(Victory().defeatString))

        // Our status: each victory's milestones, the one being worked on with its steps
        putJsonArray("ourStatus") {
            for (victory in victories) addJsonObject {
                put("name", victory.name)
                put("header", Lang.t(victory.victoryScreenHeader))
                var firstIncomplete = true
                putJsonArray("milestones") {
                    for (milestone in victory.milestoneObjects) addJsonObject {
                        val done = milestone.hasBeenCompletedBy(viewer)
                        val status = when {
                            done -> "completed"
                            firstIncomplete -> { firstIncomplete = false; "partial" }
                            else -> "incomplete"
                        }
                        put("status", status)
                        put("text", plain(safe { milestone.getVictoryScreenButtonHeaderText(done, viewer) } ?: milestone.uniqueDescription))
                        if (status == "partial") putJsonArray("steps") {
                            for ((text, achieved) in milestoneSteps(milestone, victory, viewer)) addJsonObject {
                                put("text", Lang.t(text))
                                put("done", achieved)
                            }
                        }
                    }
                }
            }
        }

        // Global status: for each victory, every major civ and its next milestone
        putJsonArray("global") {
            for (victory in victories) addJsonObject {
                put("name", victory.name)
                putJsonArray("civs") {
                    val sorted = majors.filter { victory in it.victoryManager.getAvailableVictories() }
                        .sortedWith(compareBy<Civilization> { it.isDefeated() }.thenByDescending { it.victoryManager.amountMilestonesCompleted(victory) })
                    for (civ in sorted) addJsonObject {
                        writeCivName(civ, viewer)
                        put("milestonesDone", civ.victoryManager.amountMilestonesCompleted(victory))
                        put("next", plain(civ.victoryManager.getNextMilestone(victory)?.let { safe { it.getVictoryScreenButtonHeaderText(false, civ) } } ?: "Done!"))
                    }
                }
            }
        }

        // Rankings, charts and demographics: Unciv shows them when the game allows (or once the game is over)
        val showStats = params.showCivilizationStats == true
        val visible = majors.filter { gameOver || viewer.isSpectator() || !params.hideOtherCivilizationStats || it == viewer }
        val rankingTypes = RankingType.filteredEntries(params)
        put("showRankings", gameOver || (showStats && params.showRankings))
        put("showCharts", gameOver || (showStats && params.showCharts))
        put("showDemographics", gameOver || (showStats && params.showDemographics))
        putJsonArray("rankingTypes") { rankingTypes.forEach { add(it.name) } }
        putJsonArray("rankings") {
            for (type in rankingTypes) addJsonObject {
                put("type", type.name)
                put("label", Lang.t(type.label))
                putJsonArray("civs") {
                    for (civ in visible.sortedBy { it.civName }.sortedByDescending { if (it.isDefeated()) Int.MIN_VALUE else it.getStatForRanking(type) }) addJsonObject {
                        writeCivName(civ, viewer)
                        put("value", civ.getStatForRanking(type))
                    }
                }
            }
        }
        // Charts: each civ's history ({turn: {type: value}}), like Unciv's line charts
        putJsonArray("history") {
            for (civ in visible) addJsonObject {
                writeCivName(civ, viewer)
                putJsonObject("turns") {
                    for ((turn, values) in civ.statsHistory.toSortedMap()) putJsonObject(turn.toString()) {
                        for ((type, value) in values) put(type.name, value)
                    }
                }
            }
        }
        // Demographics: your value, your rank, and the best / average / worst of the others
        putJsonArray("demographics") {
            val alive = majors.filter { it.isAlive() }
            for (type in rankingTypes) addJsonObject {
                put("type", type.name)
                put("label", Lang.t(type.label))
                val values = alive.map { it to it.getStatForRanking(type) }.sortedByDescending { it.second }
                put("value", viewer.getStatForRanking(type))
                put("rank", values.indexOfFirst { it.first == viewer } + 1)
                values.firstOrNull()?.let { (civ, value) -> put("best", value); putJsonObject("bestCiv") { writeCivName(civ, viewer) } }
                if (values.isNotEmpty()) put("average", values.sumOf { it.second } / values.size)
                values.lastOrNull()?.let { (civ, value) -> put("worst", value); putJsonObject("worstCiv") { writeCivName(civ, viewer) } }
            }
        }
    }

    private fun JsonObjectBuilder.writeCivName(civ: Civilization, viewer: Civilization) {
        val known = civ == viewer || viewer.knows(civ) || civ.isDefeated() || viewer.gameInfo.victoryData != null
        put("name", if (known) civ.civName else Constants.unknownNationName)
        put("nation", if (known) civ.nation.name else "")
        put("known", known)
        put("isYou", civ == viewer)
        put("defeated", civ.isDefeated())
    }

    /** Unciv's untranslated text keeps its translation markers ({...} and [...]): drop them. */
    /** Text for people to read: Unciv's translation in the chosen language (see [Lang.t]). */
    fun plain(text: String) = Lang.t(text)

    /** Unciv's text with its {} and [] marks taken out, untranslated (for matching names, and when translating fails). */
    fun stripBrackets(text: String) = text.replace("{", "").replace("}", "").replace("[", "").replace("]", "")

    private fun <T> safe(block: () -> T): T? = try { block() } catch (ex: Exception) { System.err.println("Victory screen: $ex"); null }

    /** The steps of the milestone being worked on - Unciv's victory screen buttons, as (text, achieved). */
    private fun milestoneSteps(milestone: Milestone, victory: Victory, civ: Civilization): List<Pair<String, Boolean>> {
        val steps = ArrayList<Pair<String, Boolean>>()
        val game = civ.gameInfo
        val hideCount = civ.shouldHideCivCount()
        when (milestone.type) {
            MilestoneType.AddedSSPartsInCapital -> {
                val done = civ.victoryManager.currentsSpaceshipParts
                val remaining = victory.requiredSpaceshipPartsAsCounter.clone()
                remaining.remove(done)
                for ((part, count) in done) repeat(count) { steps.add(part to true) }
                for ((part, count) in remaining) repeat(count) { if (count > 0) steps.add(part to false) }
            }
            MilestoneType.DestroyAllPlayers -> {
                for (other in game.civilizations) {
                    if (other == civ || !other.isMajorCiv()) continue
                    if (hideCount && !civ.knows(other)) continue
                    val name = if (civ.knows(other) || other.isDefeated()) other.civName else Constants.unknownNationName
                    steps.add("Destroy [$name]" to other.isDefeated())
                }
                if (hideCount) steps.add("Destroy ? * ${Constants.unknownNationName}" to false)
            }
            MilestoneType.CaptureAllCapitals -> {
                val capitals = game.getCities().filter { it.isOriginalCapital }.associateBy { it.foundingCivObject }
                for (other in game.civilizations.filter { it.isMajorCiv() }) {
                    val city = capitals[other]
                    if (city != null) {
                        val known = civ.hasExplored(city.getCenterTile())
                        if (hideCount && !known) continue
                        steps.add("Capture [${if (known) city.name else Constants.unknownCityName}]" to (city.civ == civ))
                    } else {
                        val name = if (civ.knows(other) || other.isDefeated()) other.civName else Constants.unknownNationName
                        steps.add("Destroy [$name]" to other.isDefeated())
                    }
                }
                if (hideCount) steps.add("Capture ? * ${Constants.unknownCityName}" to false)
            }
            MilestoneType.CompletePolicyBranches -> {
                for (branch in game.ruleset.policyBranches.values) {
                    val finisher = branch.policies.lastOrNull()?.name ?: continue
                    steps.add(finisher to civ.policies.isAdopted(finisher))
                }
            }
            MilestoneType.MoreCountableThanEachPlayer -> {
                for (other in game.civilizations) {
                    if (!milestone.getMoreCountableThanOtherCivRelevant(civ, other)) continue
                    if (hideCount && !civ.knows(other)) continue
                    val name = if (civ.knows(other)) other.civName else Constants.unknownNationName
                    val percent = milestone.getMoreCountableThanOtherCivPercent(civ, other)
                    steps.add((if (percent < 1f || percent >= 200f) name else "$name ${percent.toInt()}%") to (percent > 100f))
                }
                if (hideCount) steps.add(Constants.unknownNationName to false)
            }
            MilestoneType.WorldReligion -> {
                val religion = civ.religionManager.religion
                for (other in game.civilizations.filter { it.isMajorCiv() && it.isAlive() }) {
                    if (hideCount && !civ.knows(other)) continue
                    val name = if (civ.knows(other)) other.civName else Constants.unknownNationName
                    val met = religion != null && (!religion.isPantheon() || civ == other) && other.religionManager.isMajorityReligionForCiv(religion)
                    steps.add("Majority religion of [$name]" to met)
                }
            }
            else -> {}
        }
        return steps
    }

    /** Victory screen's "One more turn...!": keep playing after someone has won. */
    fun oneMoreTurn(game: GameInfo) {
        game.oneMoreTurnMode = true
    }

    // endregion
}
