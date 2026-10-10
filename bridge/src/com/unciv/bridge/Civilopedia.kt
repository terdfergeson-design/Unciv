package com.unciv.bridge

import com.unciv.logic.GameInfo
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.unique.IHasUniques
import com.unciv.models.ruleset.unit.UnitNameGroup
import com.unciv.models.stats.INamed
import com.unciv.models.translations.tr
import com.unciv.ui.screens.civilopediascreen.CivilopediaCategories
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.ICivilopediaText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Unciv's Civilopedia (CivilopediaScreen): the same categories, entries and pages, built from the same ruleset
 * objects - so mods' entries and their civilopediaText come along too.
 *
 * - [categories]: every category with its entries, in Unciv's order (sort group, then name), each entry with its
 *   sub-category heading when it has one; terrains carry their colour, nations theirs, for the icons.
 * - [entry]: one page as Unciv's formatted lines (FormattedLine: text, link, icon, header, size, indent, colour,
 *   separator, starred, centered, extraImage...), text translated the way Unciv shows it.
 *
 * Works without a game too (the main menu): it then shows the Gods & Kings ruleset, as Unciv's own menu does.
 */
object Civilopedia {
    private fun ruleset(game: GameInfo?): Ruleset =
        game?.ruleset
            ?: RulesetCache[BaseRuleset.Civ_V_GnK.fullName]
            ?: RulesetCache.getVanillaRuleset()

    private fun religionEnabled(game: GameInfo?, ruleset: Ruleset) =
        if (game != null) game.isReligionEnabled() else ruleset.beliefs.isNotEmpty()

    private fun shown(item: ICivilopediaText, game: GameInfo?, ruleset: Ruleset) =
        item !is IHasUniques || !item.isHiddenFromCivilopedia(game, ruleset)

    private fun items(category: CivilopediaCategories, game: GameInfo?, ruleset: Ruleset): List<ICivilopediaText> =
        try {
            category.getCategoryIterator(ruleset, game).filter { shown(it, game, ruleset) }
                .sortedWith(compareBy<ICivilopediaText> { it.getSortGroup(ruleset) }.thenBy { text(name(it), hideIcons = true).lowercase() })
        } catch (ex: Exception) {
            System.err.println("Civilopedia: couldn't list ${category.name}: $ex")
            emptyList()
        }

    private fun name(item: ICivilopediaText) = (item as? INamed)?.name ?: ""

    /** Unciv's translation (English here): placeholders filled in, conditionals worded; plain text if that fails. */
    fun text(raw: String, hideIcons: Boolean = false): String =
        try { raw.tr(hideIcons = hideIcons) } catch (_: Exception) { Gameplay.plain(raw) }

    fun categories(game: GameInfo?): JsonObject {
        val ruleset = ruleset(game)
        val religion = religionEnabled(game, ruleset)
        return buildJsonObject {
            put("ruleset", ruleset.name.ifEmpty { ruleset.mods.joinToString() })
            putJsonArray("categories") {
                for (category in CivilopediaCategories.entries) {
                    if (!religion && category == CivilopediaCategories.Belief) continue
                    val list = items(category, game, ruleset)
                    if (list.isEmpty()) continue
                    addJsonObject {
                        put("key", category.name)
                        put("label", category.label)
                        put("icon", category.headerIcon)
                        putJsonArray("entries") {
                            for (item in list) addJsonObject {
                                put("name", name(item))
                                put("label", text(name(item), hideIcons = true))
                                item.getSubCategory(ruleset)?.let { put("sub", text(it)) }
                                when (item) {
                                    is Terrain -> item.RGB?.let { rgb -> putJsonArray("rgb") { for (c in rgb) add(c) } }
                                    is Nation -> {
                                        put("outer", colour(item.getOuterColor()))
                                        put("inner", colour(item.getInnerColor()))
                                        item.cityStateType?.let { if (item.isCityState) put("cityStateType", it) }
                                    }
                                    is UnitNameGroup -> item.getUnits(ruleset).firstOrNull()?.let { put("unit", it.name) }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun colour(color: com.badlogic.gdx.graphics.Color) = "#" + color.toString().take(6)

    /** params: link - "Category/Name" (a category's name or label, as Unciv's links have it). */
    fun entry(game: GameInfo?, link: String): JsonObject {
        val ruleset = ruleset(game)
        val parts = link.split('/', limit = 2)
        val category = CivilopediaCategories.fromLink(parts[0]) ?: throw BridgeException("No Civilopedia category '${parts[0]}'")
        val list = items(category, game, ruleset)
        val item = (if (parts.size > 1) list.firstOrNull { name(it) == parts[1] } else list.firstOrNull())
            ?: throw BridgeException("No Civilopedia entry '$link'")
        val assembled = try { item.assembleCivilopediaText(ruleset).civilopediaText } catch (ex: Exception) {
            System.err.println("Civilopedia: couldn't assemble $link: $ex")
            item.civilopediaText
        }
        return buildJsonObject {
            put("category", category.name)
            put("name", name(item))
            put("link", "${category.name}/${name(item)}")
            put("lines", lines(assembled))
        }
    }

    /** Formatted lines as JSON (only the properties that are set). */
    fun lines(lines: List<FormattedLine>): JsonArray = buildJsonArray {
        for (line in lines) addJsonObject {
            if (line.text.isNotEmpty()) put("text", text(line.text))
            if (line.link.isNotEmpty()) put("link", line.link)
            val icon = line.icon.ifEmpty { if (line.linkType == FormattedLine.LinkType.Internal) line.link else "" }
            if (icon.isNotEmpty()) put("icon", icon)
            if (line.extraImage.isNotEmpty()) put("extraImage", line.extraImage)
            if (!line.imageSize.isNaN()) put("imageSize", line.imageSize)
            if (line.size != Int.MIN_VALUE) put("size", line.size)
            if (line.header != 0) put("header", line.header)
            if (line.indent != 0) put("indent", line.indent)
            if (!line.padding.isNaN()) put("padding", line.padding)
            if (line.color.isNotEmpty()) put("color", colour(line.displayColor))
            if (line.separator) put("separator", true)
            if (line.starred) put("starred", true)
            if (line.centered) put("centered", true)
            if (line.iconCrossed) put("iconCrossed", true)
        }
    }
}
