# Unciv bridge protocol (v12)

TCP on `127.0.0.1:8765` (change with `--port`). Each message is one line of UTF-8 JSON.

```
→ {"id": 1, "method": "moveUnit", "params": {"unitId": 12, "x": 3, "y": -2}}
← {"id": 1, "ok": true, "result": {...}}
← {"id": 1, "ok": false, "error": "Warrior can't reach that tile"}
```

Requests are handled one at a time, in order. Everything you read is filtered through Unciv's
View API, so it only contains what the human player is allowed to see.

## Coordinates
Each tile has Unciv hex coordinates `x, y` (use these in commands) and world coordinates
`wx, wy` for drawing. Hexes are flat-topped with a circumradius of 1 world unit, so neighbouring
centres are √3 apart; `+wy` is north. In Godot, map `(wx, wy)` → `Vector3(wx, 0, -wy)`.

## Methods

| method | params | result |
|---|---|---|
| `ping` | – | `protocol`, `uncivVersion`, `gameRunning` |
| `listOptions` | `ruleset?` | allowed values for every newGame option: `rulesets`, `nations[{name, leader, outerColor, innerColor, uniqueName, uniqueText}]`, `cityStatesAvailable`, `difficulties`, `speeds`, `eras`, `victoryTypes`, `mapSizes`, `mapShapes`, `mapTypes`, `mapResources` |
| `getSetup` | – | `setup` (the options the last newGame used, or the defaults - what "Quick start" plays), `isLastSetup` |
| `newGame` | all optional (defaults from `getSetup`): `civ` ("Random" or a nation), `ruleset`, `difficulty`, `speed`, `startingEra`, `aiPlayers`, `cityStates`, `victoryTypes[]`, `mapSize` (Tiny…Huge), `mapShape`, `mapType`, `mapResources`, `noRuins`, `noNaturalWonders`, `noBarbarians`, `ragingBarbarians`, `oneCityChallenge`, `noCityRazing`, `nuclearWeapons`, `espionage`, `seed`. Remembered for `getSetup` unless `remember: false` (test games: also no autosave). World wrap is always off for now | summary: `gameId`, `ruleset`, `turn`, `playerCiv`, `tileCount`, `mapRadius` |
| `loadGame` | `name` (from `listSaves`) or `path` – any Unciv save file | summary |
| `listSaves` | – | `folder`, `saves[{name, modified (ms since 1970), autosave}]` newest first. The bridge keeps its saves in `~/.unciv-vr/SaveFiles` and writes `Autosave` there after every `endTurn` |
| `saveGame` | `name` (into the bridge's save folder) or `path` | `path` – a normal Unciv save the desktop game can open |
| `getRuleset` | – | `terrains{name: {type, rgb[r,g,b], impassable}}`, `resources{name: {type}}`, `units{name: {unitType, strength, rangedStrength, movement}}` |
| `getState` | `since?` (a `revision` from an earlier getState) | see below. Adds `revision` and `full`; with a matching `since`, `full` is false and `tiles` holds only the tiles that changed (merge them by `x, y`) |
| `getTile` | `x, y` | that tile as in `getState` plus (when explored) `yields{food, production, gold, science, culture, faith}`, `defenseBonus` (percent), `impassable`, `worked`, `workedBy?`, `pillaged` |
| `getUnit` | `unitId` | own-unit fields + `reachable[{x,y}]` (this turn) + `actions[{type, title, enabled, current}]` + `targets[{x, y, fromX, fromY, name, civ, health, maxHealth, isCity, expectedDamageToDefender, expectedDamageToAttacker}]` + `improvements[{name, turns}]` + `tileWork{improvement, inProgress, turnsLeft}` + `xp, xpForNextPromotion, canPromote, promotions[]` + `promotionChoices[{name, description}]` (only when `canPromote`) |
| `moveUnit` | `unitId, x, y` | `moved`, `queued`, `x`, `y`, `movementLeft`. Moves as far as it can this turn; if it isn't there yet (`queued`), it keeps heading there on later turns |
| `previewMove` | `unitId, x, y` | `canReach`, `turns`, `stops[{x, y}]` (where the unit ends each turn; the last is the target). Doesn't move anything |
| `unitAction` | `unitId, action` (a type from `getUnit().actions`, e.g. `FoundCity`, `Fortify`, `Sleep`, `Explore`, `Skip`), `title?` | `done`, `unitStillExists` |
| `attack` | `unitId, x, y` (a tile from `getUnit().targets`) | `attacked`, `defender`, `damageToDefender`, `damageToAttacker`, `attackerAlive`, `attackerHealth`, `defenderDefeated`, `defenderHealth`, `cityCaptured`, `x`, `y` (where the attacker ended up). Melee units move next to the target first if needed |
| `buildImprovement` | `unitId, improvement` (a name from `getUnit().improvements`, including "Cancel improvement order") | `improvementInProgress`, `turnsLeft` |
| `getCity` | `name` or `x, y` (own city) | `name, x, y, population, foodStored, foodToGrow, turnsToGrowth, turnsToStarvation, health, maxHealth, isCapital, isPuppet`, `yields{food, production, gold, science, culture, faith}`, `queue[{index, name, turns}]`, `buildings[]`, `options[{name, kind (unit/building/wonder/perpetual), turns, cost, buildable, goldCost?, canBuy?}]` |
| `setProduction` | city (`name` or `x, y`), `construction`, `mode` ("now" = build next, default; "queue" = add to the end) | the updated `getCity` result |
| `removeFromQueue` | city, `index` | the updated `getCity` result |
| `buyConstruction` | city, `construction` | the updated `getCity` result (bought with gold) |
| `getTechs` | – | `current, freeTechs, sciencePerTurn, queue[]`, `techs[{name, era, column, row, cost, progress, researched, researchable, queueIndex, turns?, prerequisites[], unlocks[{name, kind: unit / building / wonder / resource / improvement / obsolete}]}]` |
| `setResearch` | `tech` | the updated `getTechs` result. Queues missing prerequisites first; uses a free tech if you have one |
| `describeTech` | `tech` | `name`, `description` - Unciv's full text: effects, units / buildings / wonders enabled, what it obsoletes and reveals |
| `getYields` | `tiles: [[x, y], ...]` | `tiles[{x, y, food?, production?, gold?, science?, culture?, faith?}]` for the explored ones (non-zero yields only) - for the tile-yields overlay |
| `getPolicies` | – | `culture, cultureNeeded, culturePerTurn, freePolicies, canAdopt`, `branches[{name, era, adopted, available, pickable, description, policies[{name, row, column, requires[], adopted, available, pickable, isComplete, description}]}]`. `available` = allowed by the rules (culture permitting); `pickable` = can be adopted right now |
| `adoptPolicy` | `policy` (a branch name opens the branch) | the updated `getPolicies` result |
| `getDiplomacy` | – | `yourScore`, `civs[{name, leader, outerColor, innerColor, isCityState, alive, atWar, status, relationship, opinionOfYou?, influence?, ally?, cityStateType?, score, cities, turnsOfPeaceTreatyLeft, canDeclareWar, canOfferPeace}]` for every civ you have met |
| `declareWar` | `civ` | the updated `getDiplomacy` result |
| `offerPeace` | `civ` | `accepted`, `message`, `diplomacy` (updated). City-states accept; major civs judge it like any AI trade, and a refusal blocks asking again for 3 turns |
| `getTradeOptions` | `civ` (a major civ you have met) | `civ, yourGold, theirGold`, `ours[]` / `theirs[]`: `{name, type, label, amount, duration, countable}` - what each side could offer |
| `evaluateTrade` | `civ`, `ours[]`, `theirs[]` (`{name, type, amount}` from getTradeOptions) | `acceptable`, `value` (their AI's view: >= 0 means yes; how far below 0 is roughly how much more they want) |
| `proposeTrade` | same as evaluateTrade | `accepted`, `message`, `diplomacy`. They decide right away, with the same judgement their AI uses (Unciv itself lets them decide on their turn) |
| `promote` | `unitId, promotion` (from `getUnit().promotionChoices`) | the updated `getUnit` result |
| `setSettings` | optional `automatedUnitsMoveOnTurnStart` (bool) | the current settings. false (default): your automated units move when you end your turn; true: at the start of your next turn |
| `shutdown` | – | `shuttingDown`; the bridge exits right after replying (the client uses this for bridges it launched itself) |
| `endTurn` | – (your automated / exploring units and multi-turn moves act first, then the AI players) | `turn`, `currentPlayer`, `yourTurn`, `gameOver`, `winner?`, `victoryType?`, `defeated`, `notifications[{text, category}]` |

### `getState`
```
turn, year, currentPlayer, playerCiv,
player: {gold, goldPerTurn, science, culture, faith, happiness, currentTech, canResearch, freeTechs,
         culturePoints, cultureForNextPolicy, faithPoints, population},
civs:   [{name, nation, cityStateType?, known, outerColor, innerColor, isCityState, isBarbarian, isHuman, alive}],
tiles:  [{x, y, wx, wy, explored,
          // only when explored:
          visible, base, features[], wonder?, resource?, improvement?, road,
          river?{bottom, bottomLeft, bottomRight}, owner?, worked? (one of your cities works it),
          city?{name, civ, population, isCapital, health, maxHealth},
          units?[{id, name, civ, health, civilian, embarked, fortified, unitType,
                  moves?[{x, y, type}] (recent positions, oldest first, ending where it is now)}]  // only when visible
        }],
units:  [own units: {id, name, civ, health, civilian, embarked, fortified, x, y, movement,
          maxMovement, idle, sleeping, moving, automated, strength, rangedStrength, range}],
cities: [own cities: {name, x, y, population, isCapital, health, maxHealth, producing, producingTurns, turnsToGrowth}]
```
Colors are `rrggbbaa` hex strings (Godot: `Color.html(s)`).

## Known gaps (planned)
- Unit actions that open one of Unciv's own popups or screens (disband, escort, swap) aren't available
  yet. Worker improvements go through `buildImprovement` and promotions through `promote` instead.
- Your automated units move when you end your turn (Unciv's "Move automated units" step), unless
  `setSettings` asks for the start of the turn instead.
- Conquering a city lets Unciv's AI decide whether to annex, puppet or raze it (no popup yet).
- No tile working / specialists / diplomacy / policies methods yet.
- `getState` still builds every tile on the bridge side each call; only changed tiles go over the wire when `since` is used.
