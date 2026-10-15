# Unciv bridge protocol (v26)

TCP on `127.0.0.1:8765` (change with `--port`). Each message is one line of UTF-8 JSON.
Other options: `--exit-when-idle <seconds>` (quit once no client has been connected that long; the Godot client
starts its bridge with 180 so an orphaned one doesn't linger), `--debug` (enables the `debug` method), `--verbose`.
The bridge saves the running game as the autosave before it exits (on `shutdown` or when idle), unless the game
was started with `remember: false`. Clients should compare `ping`'s `protocol` with the version they expect.

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
| `loadGame` | `name` (from `listSaves`) or `path` – any Unciv save file; `remember: false` (tests) keeps it from autosaving over the player's autosave | summary |
| `listSaves` | – | `folder`, `saves[{name, modified (ms since 1970), autosave}]` newest first. The bridge keeps its saves in `~/.unciv-vr/SaveFiles` and writes `Autosave` there after every `endTurn` |
| `saveGame` | `name` (into the bridge's save folder) or `path` | `path` – a normal Unciv save the desktop game can open |
| `getRuleset` | – | `terrains{name: {type, rgb[r,g,b], impassable}}`, `resources{name: {type}}`, `units{name: {unitType, strength, rangedStrength, movement, replaces?, uniqueTo?, attackSound?, domain (Land / Water / Air)}}` |
| `getState` | `since?` (a `revision` from an earlier getState) | see below. Adds `revision` and `full`; with a matching `since`, `full` is false and `tiles` holds only the tiles that changed (merge them by `x, y`) |
| `getTile` | `x, y` | that tile as in `getState` plus (when explored) `yields{food, production, gold, science, culture, faith}`, `defenseBonus` (percent), `impassable`, `worked`, `workedBy?`, `pillaged` |
| `getUnit` | `unitId` | own-unit fields + `reachable[{x,y}]` (this turn) + `actions[{type, title, enabled, current, confirm?, target? ("tile"), targets?[{x,y}] (no list: any tile may be tried), destination?{x,y}}]` + `due`, `escorting`, `destination?` + `targets[{x, y, fromX, fromY, name, civ, health, maxHealth, isCity, expectedDamageToDefender, expectedDamageToAttacker, kind? (nuke / airSweep: any tile in range)}]` + `improvements[{name, turns}]` + `tileWork{improvement, inProgress, turnsLeft}` + `xp, xpForNextPromotion, canPromote, promotions[]` + `promotionChoices[{name, description}]` (only when `canPromote`) |
| `moveUnit` | `unitId, x, y` | `moved`, `queued`, `x`, `y`, `movementLeft`. Moves as far as it can this turn; if it isn't there yet (`queued`), it keeps heading there on later turns |
| `previewMove` | `unitId, x, y` | `canReach`, `turns`, `stops[{x, y}]` (where the unit ends each turn; the last is the target). Doesn't move anything |
| `unitAction` | `unitId, action` (a type from `getUnit().actions`, e.g. `FoundCity`, `Fortify`, `Sleep`, `Explore`, `Skip`, `DisbandUnit`, `SwapUnits`, `ConnectRoad`, `EscortFormation`, `Pillage`), `title?`, `x?, y?` (actions with `target: "tile"`), `confirm?` | `done` (false when the action has a `confirm` question and `confirm` wasn't true: then `confirm` holds the question), `action`, `unitStillExists` |
| `attack` | `unitId, x, y` (a tile from `getUnit().targets`) | `attacked`, `defender`, `damageToDefender`, `damageToAttacker`, `attackerAlive`, `attackerHealth`, `defenderDefeated`, `defenderHealth`, `cityCaptured`, `x`, `y` (where the attacker ended up). Melee units move next to the target first if needed |
| `buildImprovement` | `unitId, improvement` (a name from `getUnit().improvements`, including "Cancel improvement order") | `improvementInProgress`, `turnsLeft` |
| `getCity` | `name` or `x, y` (own city) | `name, x, y, population, foodStored, foodToGrow, turnsToGrowth, turnsToStarvation, health, maxHealth, isCapital, isPuppet`, `yields{food, production, gold, science, culture, faith}`, `queue[{index, name, turns}]`, `buildings[]`, `options[{name, kind (unit/building/wonder/perpetual), turns, cost, buildable, goldCost?, canBuy?}]` |
| `getCity` (v13 additions) | | also: `stats{food, production, gold, science, culture, happiness, faith}`, `focus`, `focusOptions[{name, label, stats[]}]`, `avoidGrowth`, `manualSpecialists`, `freePopulation`, `growthStatus` (growing / starving / stopped / foodToProduction), `expansion{cultureStored, cultureToNextTile, turns, canExpand}`, `defense`, `rangedStrength`, `resistanceTurns?`, `wltkTurns?`, `demandedResource?`, `resources[{name, amount}]` (city-wide), `greatPeople[{name, perTurn, current, needed}]`, `specialists[{name, assigned, max, stats, color[r,g,b], greatPersonPoints}]`, `religionEnabled`, `religion?{majority?, holyCityOf?, holyCityBlocked, followers[{name, followers, pressure}]}`, `buildingDetails[{name, kind (wonder/specialist/other), stats, specialistSlots[{name, filled}], free, sellGold?, canSell?}]`, `tiles[{x, y, state, yields, buyCost?, canAfford?, owner?, workedBy?, nextToOwn?}]` for every explored tile in range (state: center / worked / locked / notWorked / providesYield / noYield / blockaded / otherCity / outOfRange / buyable / foreign), `workRange`, `viewRange`, `cities[]` (yours, for paging), `isBeingRazed`, `canAnnex`, `canRaze`, `canChange`. Queue entries add `progress`, `cost`, `kind`, `buy[]`; options add `canQueue`, `needsTile?`, `buy[{stat, cost, allowed, blockedByUnit}]`, `why[]` (when not buildable) |
| `cityTile` | city, `tileX, tileY`, `action`: toggle (Unciv's click) / work / unwork / lock / unlock / buy | the updated `getCity` result |
| `setCityFocus` | city, `focus` (a name from `focusOptions`) | the updated `getCity` result |
| `setAvoidGrowth` | city, `avoid` | the updated `getCity` result |
| `resetCitizens` | city | the updated `getCity` result (locks cleared, citizens re-placed) |
| `setManualSpecialists` | city, `manual` | the updated `getCity` result |
| `setSpecialist` | city, `specialist`, `delta` (1 or -1) | the updated `getCity` result |
| `moveInQueue` | city, `index`, `to` (up / down / top / end) | the updated `getCity` result |
| `sellBuilding` | city, `building` | the updated `getCity` result (one sale per city per turn) |
| `setRazing` | city, `raze` | the updated `getCity` result |
| `annexCity` | city (a puppet) | the updated `getCity` result |
| `getOverview` | `tab`: cities / stats / trades / units / politics / resources / religion / wonders / notifications | Unciv's Empire Overview tab: `tabs[{id, state (normal / disabled / hidden)}]`, `tab`, `turn`, plus per tab - **cities**: `religionEnabled`, `cityWideResources[]`, `cities[{name, x, y, isCapital, status (razing / resistance / puppet / ""), construction, constructionTurns, population, food, gold, science, production, culture, happiness, faith, wltk, wltkTurns?, demanding?, garrison?{id, name}, defense, health, maxHealth, religion?, holyCity?, resources{}}]`; **stats**: `happiness{source: value}`, `gold`, `science`, `culture`, `faith?` (same shape), `goldToScience?`, `greatPeople[{name, points, needed, perTurn}]`, `greatGenerals[{name, points, needed}]`, `score{}`; **trades**: `pending[]`, `current[]` of `{civ, nation, colours, ours[text], theirs[text]}`; **units**: `supply{base, cities, population, total, inUse, deficit, productionPenalty}`, `units[{id, name, baseUnit, unitType, x, y, civilian, idle, action?, strength, rangedStrength, movement, maxMovement, closestCity?, inCity?, promotions[], canPromote, health, xp?, xpForNextPromotion?, upgrades[{to, cost, enabled}]}]`; **politics**: `civs[{name, leader, era, defeated, score, colours, policies[{branch, count}], wonders[{name, x?, y?}], wars[], defensivePacts[{civ, turns}], friends[], denounced[], alliedCityStates[]}]`, `diagram{civs[], lines[{a, b, relation}]}`, `civsInGame`; **resources**: `origins[]`, `extraOrigins[]`, `resources[{name, type, stockpiled, amounts{origin: n}, total, extra{}}]`; **religion**: `enabled, toFound, toFoundBreakdown[{text, value}], prophet?, faithForProphet?, status, religions[{name, displayName, icon, pantheon, foundingCiv, founder?, holyCity?, cities, followers, beliefs[{name, type, text}]}]`; **wonders**: `wonders[{name, realName, category, group, groupColor, status, statusText, location, x?, y?}]`; **notifications**: `turns[{turn, year, current, notifications[{text, category, icons[], actions[{type (City / Location / Tech / Policy / Diplomacy / ...), x?, y?, ...}]}]}]` |
| `upgradeUnit` | `unitId`, `to?` | upgrades it anywhere (gold), like the Units tab; returns the units tab |
| `setGoldToScience` | `percent` (0-1) | only for rulesets with Unciv's gold-to-science conversion; returns the stats tab |
| `renameCity` | city (`name` or `x, y`), `newName` | the updated `getCity` result (names must be unique) |
| `renameUnit` | `unitId`, `newName` (empty: back to its ordinary name) | the updated `getUnit` result; units you've named carry `instanceName` (in getState and getUnit; `name` stays the unit type) |
| `describeConstruction` | city, `construction` | `name`, `description` (Unciv's text for it, as its city screen shows) |
| `setProduction` | city (`name` or `x, y`), `construction`, `mode` ("now" = build next, default; "queue" = add to the end) | the updated `getCity` result |
| `removeFromQueue` | city, `index` | the updated `getCity` result |
| `buyConstruction` | city, `construction`, `stat?` (gold - default - or faith etc. where allowed), `queueIndex?` | the updated `getCity` result |
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
| `proposeTrade` | same as evaluateTrade | `accepted`, `counteroffer`, `message`, `diplomacy` (+ `counterKey`, `theyGive[]`, `youGive[]` with a counteroffer). Their AI answers right away the way it answers on its turn in Unciv: yes, no, or a counteroffer - which then waits as one of their trade requests in `getState().alerts` |
| `promote` | `unitId, promotion` (from `getUnit().promotionChoices`) | the updated `getUnit` result |
| `setSettings` | optional booleans: `automatedUnitsMoveOnTurnStart`, `checkForDueUnits` (End turn waits until every unit has orders), and Unciv's options its game logic reads: `autoUnitCycle`, `alternateUnitCycleOrder`, `autoAssignCityProduction`, `autoBuildingRoads`, `automatedWorkersReplaceImprovements`, `stopAutomatedWorkersRemoveVegetation`, `automatedUnitsCanUpgrade`, `automatedUnitsChoosePromotions`, `citiesAutoBombardAtEndOfTurn`, `autoAssignSpecialistsInNewCities` | all of them, current values |
| **v26** | | |
| `downloadMusic` | – | `path`, `bytes`: Unciv's own music track (Options > Sound > Download music: "Thatched Villagers" by Kevin MacLeod, CC BY 4.0 - fetched from incompetech.com, its author's site, rather than Unciv's Dropbox) saved as `~/.unciv-vr/Music/Thatched Villagers - Ambient.mp3`, where the client looks for music. Fails with "Could not download music!" and the reason |
| `getState` | | `player` also has `atWar` (at war with any civ still in the game) - Unciv's turn music picks a War or Peace track by it |
| **v24** | | |
| `debug` | also `command: autoPlay`: Unciv's AI plays your civ's turn (research, policies, production, units, diplomacy, religion); end it with `endTurn` - for `autoplay_test.py` | |
| (errors) | | a failure that is a bug in the bridge, rather than a refusal, also carries `internal: true`, and its `error` names the exception and where it happened |
| `setProduction` | `mode: now` on a building already in the queue moves it to the front | |
| `chooseGreatPerson` | when there's no space next to the capital it fails as before, but the free great person then no longer stops this turn from ending (like Unciv's picker, which can be closed then) | |
| `getLanguages` | – | `language` (the current one), `languages[{name, label, tag, percent}]`: English and every language Unciv has a translation file for - `name` is Unciv's ("German", "Simplified_Chinese"), `tag` its IETF tag ("de-DE"), `percent` how complete Unciv's translation is |
| `getTranslations` | – | `language`, `tag`, `entries{English: translated}`: every plain entry of the language's Unciv translation file (no placeholders) - unit, building, tech, nation, city and other names and Unciv's own words. Empty for English |
| `setSettings` | also `language` (a `name` from `getLanguages`; loads its file the first time) | also `language` |
| (all) | | Text for people to read now comes in the chosen language, through Unciv's translations (`tr()`): messages and popups (`alerts` title, text, option labels and descriptions), `blockers` text, notifications, status `message`s, descriptions, quests, victory texts, trade offer labels, unit `action`s... Text Unciv has no translation for stays English (with its names translated). Names that are also ids stay English everywhere (`name`, unit action `title`); unit actions gain `label`, the title translated, to show. In English, everything reads as before |
| **v23** | | |
| `getUnit` | | for a unit that can found a city, also `suggestedCitySpots[{x, y, value}]`: Unciv's suggested city locations (the three best explored tiles within 5, worth at least 50, as its map marks them) |
| **v22** | | |
| `getCivilopedia` | – (works without a game too: then the Gods & Kings ruleset) | Unciv's Civilopedia categories, in its order: `ruleset`, `categories[{key, label, icon, entries[{name, label, sub?, rgb? (terrains), outer?, inner?, cityStateType? (nations), unit? (unit name groups)}]}]`. `key` is Unciv's CivilopediaCategories name (`Building`, `Unit`, `Technology`, `Tutorial`...); entries are sorted and filtered (hidden ones, religion when it's off) as Unciv's screen does, `sub` is the heading they go under |
| `getCivilopediaEntry` | `link` (`Category/Name`, as Unciv's links: the category's key or label) | `category`, `name`, `link`, `lines[]`: Unciv's formatted lines, only the properties set: `text` (translated as Unciv shows it; Unciv's icon characters ⚙ ¤ ⁂ ⍾ ♪... included), `link` (another entry, or an https:// address), `icon` (an entry to draw the icon of), `extraImage` (a file in ExtraImages or an icon path), `imageSize`, `size`, `header` (1-8), `indent`, `padding`, `color` (`#rrggbb`), `separator`, `starred`, `centered`, `iconCrossed` |
| `getState` | | `alerts` also bring Unciv's tutorials, after the game's own, one page at a time: `type` "Tutorial", `tutorial` (its name), `step`, `steps`, one option (`next`, or `close` on the last page). Answering the last page marks it seen. And `tutorialTask?{name, task, lines[], choices[{index, text}]}`: the first of Unciv's tutorial tasks (the "Tutorial Task: [...]" events) available now. Both only with `setSettings showTutorials`. The same goes for every reply that carries `blockers` and `alerts` |
| `setSettings` | also `showTutorials` (off until a client turns it on), `resetTutorials` (forget what's been seen and done) | also `showTutorials` |
| `showTutorial` | `name`: a TutorialTrigger (`CultureAndPolicies` when the policies open, `Experience` for promotions...) - shown once, if tutorials are on | `blockers`, `alerts`, `tutorialTask?` |
| `tutorialTaskDone` | `task`: one the client sees done itself ("Enter city screen", "Open the options table", "See your stats breakdown"); the bridge marks moving a unit, founding a city, picking research or production, ending a turn and working a tile itself | `blockers`, `alerts`, `tutorialTask?` |
| `chooseTutorialTask` | `name` (the task's event), `index` (from its `choices`) | `blockers`, `alerts`, `tutorialTask?` |
| **v21** | | |
| `getState` | | your units also carry `due` (still waiting for orders this turn), `canPromote`, and `action`: what the unit is doing in the words of Unciv's unit list (`Fortified`, `Guarding`, `Sleeping`, `Exploring`, `Moving`, `Building <improvement>`, `Automated`), left out when it is waiting for orders |
| **v20** | | |
| `getUnit` | | each action also carries `sound`: the name of the sound Unciv plays for it (`fortify`, `promote`, `chimes`, `click`...), a file in Unciv's `android/assets/sounds` |
| `getCity` | | also `ambience`: the era's city noise (`cityAncient` ... `cityFuture`), which Unciv loops while you are in a city |
| **v19** | | |
| `previewMove` | | also `canStay`: whether the unit could end its move on the tile. A path can lead to a tile it may only pass through - one that already holds as many units as Unciv allows, say - and a move there stops short of it |
| **v18** | | |
| `getState` | – | also `game{ruleset, mods[], difficulty, speed, mapSize}` |
| `setSettings` | also optional integers `notificationsLogMaxTurns` (3-15, Options > Gameplay) and `turnsBetweenAutosaves` (Options > Advanced; the bridge's autosave follows it) | also those two |
| **v16** | | |
| `answerAlert` | `key`, `option` (from `getState().alerts`) | `message`; for a trade request's `counter`: also `civ`, `ours[]`, `theirs[]` (their offer, to put on the trade table) |
| `dismissBlocker` | `type`: PickPolicy or MoveSpies (Unciv asks once; opening the screen dismisses it) | `blockers`, `alerts` |
| `getGreatPersonChoices` / `chooseGreatPerson` | – / `unit` | `free`, `choices[{name, available, description}]` / `blockers`, `alerts` |
| `getVoteChoices` / `vote` | – / `civ?` (none = abstain) | `canVote`, `civs[{name, nation}]` / `blockers`, `alerts` |
| `getReligionChoices` | – | `mode` (pantheon / found / enhance / reform / null), `enabled`, `state`, `religion?`, `displayName?`, `currentBeliefs[{name, type}]`, `slots[]` (a belief type per pick), `pickIconAndName`, `icons?[{name, available}]`, `beliefs[{name, type, color, effects, available, why? (taken / yours / blocked)}]` |
| `chooseReligion` | `beliefs[]` (one per slot, in order), when founding also `icon` and `name` | the updated `getReligionChoices` result |
| `getVictory` / `oneMoreTurn` | – | Unciv's victory screen: `turn, speed, difficulty, maxTurns?, oneMoreTurnMode, defeated, winner?{civ, victoryType, turn, isYou, text}`, `ourStatus[{name, header, milestones[{status (completed / partial / incomplete), text, steps?[{text, done}]}]}]`, `global[{name, civs[{name, nation, known, isYou, defeated, milestonesDone, next}]}]`, rankings, charts, demographics, and `mapReplay{startTurn, endTurn, tiles[{x, y, states[{turn, owner?, city (N/R/C)}]}]}` (compact ownership and city-center changes) |
| `diplomacyAction` | `civ`, `action`: giftGold (`gold` 250/500/1000), giftImprovement (`x, y, improvement`), pledgeProtection, revokeProtection, tribute (`kind` gold / worker), marriage, denounce, offerFriendship, makeDemand (`demand`) | `message`, `diplomacy` |
| `cityAttack` | city, `tileX, tileY` (from `getCity().bombardTargets`) | `attacked`, `defender`, `damageToDefender`, `defenderDefeated`, `defenderHealth`, `city` (updated getCity) |
| `getEspionage` / `moveSpy` / `stageCoup` | – / `spy`, `city?` (none = hideout), `confirm?` / `spy`, `confirm?` | `enabled`, `spies[{name, rank, action, location, x?, y?, turns?, alive, canCoup?, coupChance?, stagingCoup?}]`, `cities[{name, civ, x, y, isCityState, own, spies[], canMoveHere[], promiseWarning?}]` / `done` (false: `confirm` question), `espionage` |
| `debug` | only when the bridge runs with `--debug` (tests): `command` (addStat, addTech, spawnUnit, freeGreatPerson, meet, war, unitHealth, cityHealth, reveal, popupAlert, votingFlag), ... | `result` |
| `shutdown` | – | `shuttingDown`; the bridge exits right after replying (the client uses this for bridges it launched itself) |
| `endTurn` | – (refused while `getState().blockers` isn't empty; messages that only had a close button are taken as read; your automated / exploring units and multi-turn moves act first, then the AI players) | `turn`, `currentPlayer`, `yourTurn`, `gameOver`, `winner?`, `victoryType?`, `defeated`, `notifications[{text, category}]`, `events[...]` (see below) |

### `endTurn` events (protocol v17)

What the other players did that you could see, in the order the civs played, so the client can play it back
(`TurnEvents.kt`). Unciv keeps no ordered log of the AI's actions, so the bridge compares the game before and
after the turn: where each unit was and how healthy, and the attacks Unciv remembers since each civ's turn
started (the ones its movement-arrow overlay draws), filtered with the View API's `getVisibleAttacks` (an
attack shows when either end was in sight). Within a civ, its moves come before its fights. At most 300 events.

- `{type: "move", unit, name, civ, from: [x, y], to: [x, y]}` - only where it started and ended (the client
  draws a straight hex line). A melee unit that attacked walks to the tile it attacked from; the advance or
  retreat afterwards is a second move after the attack.
- `{type: "attack", attacker?, name, civ, sound, ranged, city, from, to, defender?, defenderName, defenderCiv,
  damage, defenderDied?, defenderCity?, cityCaptured?, damageTaken?, attackerDied?}` - `attacker` is missing
  for a city bombarding or a unit that has since died (`attackerDied`); `sound` is the unit's `attackSound`;
  `damage` is the health the defender lost, split evenly when several attacks hit the same tile; the death
  (or capture of a city) is put on the last of those.

The player's own fights aren't listed: the client plays those when it makes them (`attack`, `cityAttack`).

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
cities: [own cities: {name, x, y, population, isCapital, health, maxHealth, producing, producingTurns, turnsToGrowth,
          isPuppet, isBeingRazed, canBombard}],
// v16:
blockers: [{type, text, ...}]   // what End turn waits for, in Unciv's order: AnswerAlerts, PickGreatPerson,
          // PickConstruction (city, x, y), PickTech, PickPolicy, MoveSpies, FoundPantheon / ExpandPantheon /
          // FoundReligion / EnhanceReligion / ReformReligion, WorldCongressVote, NextUnit (unitId, x, y; only
          // with setSettings checkForDueUnits)
alerts:   [{key, type, title, text, needsAnswer, civ?, nation?, leader?, isCityState?,
           options[{id, label, description?, enabled}], ...}]  // Unciv's popups: first contact, war declared,
          // demands, friendship offers, a conquered city (annex / puppet / raze / liberate), a recaptured
          // civilian, wonders and techs, golden ages, World Congress results (votes), trade requests
          // (theyGive[], youGive[]; accept / decline / counter), ...
```
`getCity` (v16) also has `canBombard`, `bombardTargets[{x, y, name, civ, health, maxHealth, expectedDamageToDefender}]`;
options with `needsTile` add `improvement` and `placeTiles[{x, y}]` (pass `tileX, tileY` to `setProduction` /
`buyConstruction`), and queue entries for them carry `tile{x, y}`. The overview has an `espionage` tab
(the `getEspionage` fields).
Colors are `rrggbbaa` hex strings (Godot: `Color.html(s)`).

## Notes and known gaps
- What tutorials you've seen and which tasks you've done are kept in `~/.unciv-vr/tutorials.json`.
- Worker improvements go through `buildImprovement` and promotions through `promote`.
- Your automated units move when you end your turn (Unciv's "Move automated units" step), unless
  `setSettings` asks for the start of the turn instead.
- Conquering a city: without Unciv's world screen its battle code makes the AI's choice first; the
  `CityConquered` alert then changes it to the player's. In the rare case the AI liberates the city
  (a city-state's former city), it's simply liberated.
- Map replay (the victory screen's Replay tab) isn't sent.
- `getState` still builds every tile on the bridge side each call; only changed tiles go over the wire when `since` is used.
