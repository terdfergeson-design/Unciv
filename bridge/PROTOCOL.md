# Unciv bridge protocol (v1)

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
| `listOptions` | `ruleset?` | `rulesets`, `nations[{name, leader, outerColor, innerColor}]`, `difficulties`, `mapSizes`, `mapShapes` |
| `newGame` | all optional: `civ`, `ruleset`, `mapSize` (Tiny…Huge, default Tiny), `mapShape`, `mapType`, `aiPlayers` (3), `cityStates` (4), `difficulty` (Prince), `seed`, `noBarbarians` | summary: `gameId`, `ruleset`, `turn`, `playerCiv`, `tileCount`, `mapRadius` |
| `loadGame` | `path` – any Unciv save file | summary |
| `saveGame` | `path` | `path` – a normal Unciv save the desktop game can open |
| `getRuleset` | – | `terrains{name: {type, rgb[r,g,b], impassable}}`, `resources{name: {type}}`, `units{name: {unitType, strength, rangedStrength, movement}}` |
| `getState` | – | see below |
| `getUnit` | `unitId` | own-unit fields + `reachable[{x,y}]` (this turn) + `actions[{type, title, enabled, current}]` |
| `moveUnit` | `unitId, x, y` | `moved`, `x`, `y`, `movementLeft`. Far targets: moves as far as it can and keeps going on later turns |
| `unitAction` | `unitId, action` (a type from `getUnit().actions`, e.g. `FoundCity`, `Fortify`, `Sleep`, `Explore`, `Skip`), `title?` | `done`, `unitStillExists` |
| `endTurn` | – | `turn`, `currentPlayer`, `yourTurn`, `gameOver`, `winner?`, `victoryType?`, `defeated`, `notifications[{text, category}]` |

### `getState`
```
turn, year, currentPlayer, playerCiv,
player: {gold, goldPerTurn, science, culture, faith, happiness, currentTech},
civs:   [{name, known, outerColor, innerColor, isCityState, isBarbarian, isHuman, alive}],
tiles:  [{x, y, wx, wy, explored,
          // only when explored:
          visible, base, features[], wonder?, resource?, improvement?, road,
          river?{bottom, bottomLeft, bottomRight}, owner?,
          city?{name, civ, population, isCapital, health, maxHealth},
          units?[{id, name, civ, health, civilian, embarked, fortified}]  // only when visible
        }],
units:  [own units: {id, name, civ, health, civilian, embarked, fortified, x, y, movement,
          maxMovement, idle, sleeping, moving, automated, strength, rangedStrength, range}],
cities: [own cities: {name, x, y, population, isCapital, health, maxHealth, producing, turnsToGrowth}]
```
Colors are `rrggbbaa` hex strings (Godot: `Color.html(s)`).

## Known gaps (planned)
- Unit actions that open one of Unciv's own popups or screens (promotions, improvement picker,
  disband, escort, swap) aren't available yet.
- No research / production / city management / combat / diplomacy methods yet.
- `getState` sends the whole map every time; fine for Tiny–Medium maps, diffs come later.
