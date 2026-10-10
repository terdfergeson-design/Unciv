package com.unciv.bridge

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.models.Counter
import com.unciv.models.Religion
import com.unciv.models.ruleset.Belief
import com.unciv.models.ruleset.BeliefType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Unciv's pantheon picker and religious beliefs picker (founding, enhancing, reforming), without the screens.
 */
object ReligionPicker {

    /** What the civ is choosing right now: pantheon, found, enhance or reform - or null for nothing. */
    fun mode(civ: Civilization): String? {
        val manager = civ.religionManager
        return when {
            !civ.gameInfo.isReligionEnabled() -> null
            manager.religionState == ReligionState.FoundingReligion -> "found"
            manager.religionState == ReligionState.EnhancingReligion -> "enhance"
            manager.canFoundOrExpandPantheon() -> "pantheon"
            manager.hasFreeBeliefs() -> "reform"
            else -> null
        }
    }

    private fun slots(civ: Civilization, mode: String): Counter<BeliefType> = when (mode) {
        "pantheon" -> Counter<BeliefType>().apply { add(BeliefType.Pantheon, 1) }
        "found" -> civ.religionManager.getBeliefsToChooseAtFounding()
        "enhance" -> civ.religionManager.getBeliefsToChooseAtEnhancing()
        else -> civ.religionManager.freeBeliefsAsEnums()
    }

    private fun slotList(civ: Civilization, mode: String): List<BeliefType> =
        slots(civ, mode).flatMap { (type, count) -> List(count) { type } }

    /** Why [belief] can't be chosen by [civ] (null: it can) - the same tests as Unciv's pickers. */
    private fun unavailableReason(civ: Civilization, belief: Belief, mode: String): String? {
        val ours = civ.religionManager.religion
        if (ours != null && ours.hasBelief(belief.name)) return "yours"
        val holder = civ.religionManager.getReligionWithBelief(belief)
        if (holder != null && holder != ours) return "taken"
        if (belief.getMatchingUniques(UniqueType.OnlyAvailable, GameContext.IgnoreConditionals).any { !it.conditionalsApply(civ.state) }) return "blocked"
        if (belief.getMatchingUniques(UniqueType.Unavailable, civ.state).any()) return "blocked"
        if (mode == "pantheon" && belief.type != BeliefType.Pantheon) return "blocked"
        return null
    }

    private fun usedIcons(civ: Civilization) = civ.gameInfo.religions.keys

    fun choices(civ: Civilization): JsonObject = buildJsonObject {
        val mode = mode(civ)
        put("mode", mode)
        put("enabled", civ.gameInfo.isReligionEnabled())
        val manager = civ.religionManager
        put("state", manager.religionState.name)
        manager.religion?.let { religion ->
            put("religion", religion.name)
            put("displayName", religion.getReligionDisplayName())
            putJsonArray("currentBeliefs") {
                for (belief in religion.getAllBeliefsOrdered()) addJsonObject { put("name", belief.name); put("type", belief.type.name) }
            }
        }
        if (mode == null) return@buildJsonObject
        putJsonArray("slots") { slotList(civ, mode).forEach { add(it.name) } }
        put("pickIconAndName", mode == "found")
        if (mode == "found") putJsonArray("icons") {
            val used = usedIcons(civ)
            for (name in civ.gameInfo.ruleset.religions) addJsonObject {
                put("name", name)
                put("available", name !in used)
            }
        }
        val slotTypes = slotList(civ, mode).toSet()
        putJsonArray("beliefs") {
            for (belief in civ.gameInfo.ruleset.beliefs.values) {
                if (belief.type == BeliefType.None) continue
                if (BeliefType.Any !in slotTypes && belief.type !in slotTypes && mode != "pantheon") continue
                if (mode == "pantheon" && belief.type != BeliefType.Pantheon) continue
                addJsonObject {
                    put("name", belief.name)
                    put("type", belief.type.name)
                    put("color", belief.type.color)
                    put("effects", belief.uniqueObjects.filter { !it.isHiddenToUsers() }.joinToString("\n") { Gameplay.plain(it.getDisplayText()) })
                    val reason = unavailableReason(civ, belief, mode)
                    put("available", reason == null)
                    if (reason != null) put("why", reason)
                }
            }
        }
    }

    /**
     * params: beliefs[] (one name per slot, in slot order), and when founding: icon (one of the ruleset's religions,
     * e.g. "Christianity") and name (what you call it).
     */
    fun choose(civ: Civilization, params: JsonObject): JsonObject {
        val mode = mode(civ) ?: throw BridgeException("There's no religious choice to make right now")
        val manager = civ.religionManager
        val ruleset = civ.gameInfo.ruleset
        val names = (params["beliefs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        val slots = slotList(civ, mode)
        if (names.size != slots.size) throw BridgeException("Pick ${slots.size} belief${if (slots.size == 1) "" else "s"}")
        if (names.toSet().size != names.size) throw BridgeException("Each belief can only be picked once")
        val beliefs = names.mapIndexed { index, name ->
            val belief = ruleset.beliefs[name] ?: throw BridgeException("Unknown belief '$name'")
            val slot = slots[index]
            if (slot != BeliefType.Any && belief.type != slot) throw BridgeException("$name isn't a ${slot.name} belief")
            unavailableReason(civ, belief, mode)?.let { throw BridgeException("$name can't be chosen ($it)") }
            belief
        }
        if (mode == "found") {
            val icon = params.requireStr("icon")
            if (icon !in ruleset.religions) throw BridgeException("Unknown religion icon '$icon'")
            if (icon in usedIcons(civ)) throw BridgeException("$icon has already been founded")
            val name = (params.str("name") ?: icon).trim()
            if (name.isEmpty() || name == Constants.noReligionName) throw BridgeException("Your religion needs a name")
            if (name != icon && name in ruleset.religions) throw BridgeException("$name is the name of another religion")
            if (civ.gameInfo.religions.values.any { it.getReligionDisplayName() == name }) throw BridgeException("There's already a religion called $name")
            // ReligionManager.foundReligion(displayName, name) is internal to Unciv's core module
            val method = manager.javaClass.methods.firstOrNull {
                it.name.startsWith("foundReligion") && it.parameterCount == 2 && it.parameterTypes.all { type -> type == String::class.java }
            } ?: throw BridgeException("This Unciv version can't found religions from the bridge")
            method.invoke(manager, name, icon)
        }
        manager.chooseBeliefs(beliefs, manager.usingFreeBeliefs())
        return choices(civ)
    }

    /** For notifications and the overview: a religion's display name. */
    fun displayName(religion: Religion?): String? = religion?.getReligionDisplayName()
}
