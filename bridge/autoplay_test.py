#!/usr/bin/env python3
"""Playtests whole games with nobody at the controls: Unciv's AI plays every civ, yours included (Unciv's
"AutoPlay"), while this script asks the bridge everything the VR client would ask, every turn. Anything that fails
is a bug a player would have hit: a crash in the bridge, or something it offered and then refused. Needs only the Python standard library.

Start a bridge with debug commands on, then run games against it:
    gradlew :bridge:run --args="--debug --port 8799"          (Windows: gradlew.bat ...)
    python bridge/autoplay_test.py --port 8799 --games 3 --map-size Small --max-turns 300

Each game: a new game (all AI, a different seed and civ each), then turn after turn:
  1. read: the full state (and the changes since last turn), every unit's card, every city's view and what it is
     building, the overview tabs, tech tree, policies, diplomacy and trade options, victory screen, espionage,
     religion picker, tile yields; the whole Civilopedia once per run
  2. answer any message that needs an answer (picking a different option each time, to cover them all)
  3. a few things a player would do, picked at random from what the cards offer (moves, attacks, promotions, unit
     actions, production, city focus, worked tiles, specialists, buying); --no-actions leaves that out
  4. let the AI play the rest of your turn (debug autoPlay), clear what still stops the turn ending, and end it
until someone wins, you're defeated, or --max-turns. Turn times are measured as it goes.

The report (autoplay-report/ by default): report.md to read, report.json with everything, and for each failure the
game saved just before it (load it in the client with --load=<file>, or with the bridge's loadGame) to reproduce it.
"""
import argparse
import json
import os
import random
import socket
import sys
import time
import traceback

OVERVIEW_TABS = ["cities", "stats", "trades", "units", "politics", "resources", "religion", "wonders", "notifications"]
# Options never picked when answering a message: "counter" opens a trade screen, which only a person can fill in
SKIP_OPTIONS = {"counter"}


class BridgeError(Exception):
    def __init__(self, message, internal=False):
        super().__init__(message)
        self.internal = internal   # a crash in the bridge, not a refusal


class Bridge:
    def __init__(self, port, timeout):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=timeout)
        self.file = self.sock.makefile("rw", encoding="utf-8", newline="\n")
        self.next_id = 1

    def call(self, method, **params):
        request = {"id": self.next_id, "method": method, "params": params}
        self.next_id += 1
        self.file.write(json.dumps(request) + "\n")
        self.file.flush()
        line = self.file.readline()
        if not line:
            raise ConnectionError("the bridge closed the connection (did it crash?)")
        response = json.loads(line)
        if not response.get("ok"):
            raise BridgeError(response.get("error", "?"), bool(response.get("internal")))
        return response["result"]


class Game:
    """One autoplayed game and what went wrong in it."""

    def __init__(self, bridge, args, index, setup):
        self.b = bridge
        self.args = args
        self.index = index
        self.setup = setup
        self.failures = []       # {turn, method, params, error, save}
        self.failure_keys = set()
        self.turn_times = []     # (turn, seconds the AI players took)
        self.read_times = []     # (turn, seconds all the reads took)
        self.calls = 0
        self.turn = 0
        self.result = "?"
        self.blockers_forced = {}
        self.notes = []          # things worth knowing that aren't failures
        self.rnd = random.Random(setup.get("seed", 0))
        self.revision = None
        self.player = ""

    # -- calls --------------------------------------------------------------------------------------------

    def call(self, method, expect_fail=False, **params):
        """A bridge call; a failure is recorded (once per method and message) and None returned."""
        self.calls += 1
        try:
            return self.b.call(method, **params)
        except BridgeError as e:
            if not expect_fail or e.internal:
                self.fail(method, params, str(e), "crash" if e.internal else "refused")
            return None

    def fail(self, method, params, error, kind="refused"):
        key = (method, error.split(" at ")[0][:120])
        if key in self.failure_keys:
            for f in self.failures:
                if (f["method"], f["error"].split(" at ")[0][:120]) == key:
                    f["count"] += 1
            return
        self.failure_keys.add(key)
        save = ""
        if len(self.failures) < self.args.max_saves:
            save = os.path.abspath(os.path.join(self.args.report, f"game{self.index}-turn{self.turn}-{method}.json"))
            try:
                self.b.call("saveGame", path=save)
            except Exception:
                save = ""
        self.failures.append({"turn": self.turn, "method": method, "params": params, "error": error, "save": save, "count": 1,
                              "kind": kind})
        print(f"    ! turn {self.turn}: {method} {json.dumps(params)[:120]} -> {error[:200]}")

    # -- one game -----------------------------------------------------------------------------------------

    def play(self):
        started = time.time()
        summary = self.call("newGame", remember=False, **self.setup)
        if summary is None:
            self.result = "couldn't start"
            return
        self.player = summary.get("playerCiv", "?")
        self.call("setSettings", checkForDueUnits=False, showTutorials=self.args.tutorials,
                  language=self.args.language or "English")
        rules = self.call("getRuleset")
        while True:
            state = self.call("getState") if self.revision is None else self.call("getState", since=self.revision)
            if state is None:
                self.result = "getState failed"
                break
            self.revision = state.get("revision")
            self.turn = int(state.get("turn", self.turn))
            if self.turn % 25 == 0 or self.revision is None:
                full = self.call("getState")   # now and then the whole map again, not just the changes
                if full is not None:
                    state = full
            t0 = time.time()
            self.read_everything(state)
            self.answer_alerts(state)
            self.read_times.append((self.turn, time.time() - t0))
            if self.args.actions:
                self.player_actions(state)

            if self.call("debug", command="autoPlay") is None:
                self.result = "autoPlay failed"
                break
            if not self.clear_blockers():
                self.result = f"stuck at turn {self.turn}"
                break
            t0 = time.time()
            ended = self.call("endTurn")
            if ended is None:
                self.result = f"endTurn failed at turn {self.turn}"
                break
            self.turn_times.append((self.turn, time.time() - t0))
            self.turn = int(ended.get("turn", self.turn + 1))
            if ended.get("gameOver"):
                self.result = f"{ended.get('winner')} won ({ended.get('victoryType')} victory) on turn {self.turn}"
                self.call("getVictory")
                break
            if ended.get("defeated"):
                self.result = f"{self.player} was defeated on turn {self.turn}"
                break
            if self.turn >= self.args.max_turns:
                self.result = f"no winner by turn {self.turn} (--max-turns)"
                self.call("getVictory")
                break
            if self.turn % 10 == 0:
                slow = max((t for _, t in self.turn_times[-10:]), default=0)
                print(f"  game {self.index}: turn {self.turn}, {len(self.failures)} failures, slowest of the last 10 turns {slow:.1f}s")
        self.seconds = time.time() - started

    # -- what the client reads ----------------------------------------------------------------------------

    def read_everything(self, state):
        own_units = [u for u in state.get("units", []) if u.get("civ") == self.player]
        for unit in own_units[: self.args.max_units]:
            self.call("getUnit", unitId=unit["id"])
        own_cities = [c for c in state.get("cities", []) if c.get("civ", self.player) == self.player]
        for city in own_cities[: self.args.max_cities]:
            details = self.call("getCity", name=city["name"])
            if details:
                queue = details.get("queue", [])
                current = queue[0].get("name", "") if queue else ""
                if current:
                    self.call("describeConstruction", name=city["name"], construction=current)
        for tab in OVERVIEW_TABS:
            self.call("getOverview", tab=tab)
        techs = self.call("getTechs")
        if techs and self.turn % 10 == 0:
            for tech in techs.get("techs", [])[:: max(1, len(techs.get("techs", [])) // 8)]:
                self.call("describeTech", tech=tech["name"])
        self.call("getPolicies")
        diplomacy = self.call("getDiplomacy")
        if diplomacy and self.turn % 5 == 0:
            for civ in diplomacy.get("civs", [])[:3]:
                if not civ.get("isCityState") and not civ.get("atWar", False):
                    self.call("getTradeOptions", expect_fail=True, civ=civ["name"])
        self.call("getVictory")
        self.call("getEspionage")
        self.call("getReligionChoices")
        tiles = [[t["x"], t["y"]] for t in state.get("tiles", [])[:200] if t.get("explored", True)]
        if tiles:
            self.call("getYields", tiles=tiles)

    # -- what a player does ---------------------------------------------------------------------------------

    def player_actions(self, state):
        """A few things a player would do this turn, picked at random from what the cards offer: moves, attacks,
        promotions, unit actions, production, focus, worked tiles, specialists, buying. The AI then plays the rest of
        the turn. Anything the bridge offered and then refused is reported."""
        rnd = self.rnd
        units = [u for u in state.get("units", []) if u.get("civ") == self.player]
        for unit in rnd.sample(units, min(3, len(units))):
            card = self.call("getUnit", unitId=unit["id"])
            if not card:
                continue
            if card.get("canPromote") and card.get("promotionChoices"):
                self.call("promote", unitId=unit["id"], promotion=rnd.choice(card["promotionChoices"])["name"])
                continue
            targets = card.get("targets", [])
            if targets and rnd.random() < 0.5:
                t = rnd.choice(targets)
                self.call("attack", unitId=unit["id"], x=t["x"], y=t["y"])
                continue
            here = (card.get("x"), card.get("y"))
            reachable = [t for t in card.get("reachable", []) if (t["x"], t["y"]) != here]
            if reachable and rnd.random() < 0.6:
                t = rnd.choice(reachable)
                self.call("previewMove", unitId=unit["id"], x=t["x"], y=t["y"])
                self.call("moveUnit", unitId=unit["id"], x=t["x"], y=t["y"])
                continue
            # Not: disbanding (it would end the test early), and showing a destination (the client draws that itself)
            actions = [a for a in card.get("actions", []) if a.get("enabled") and a["type"] not in ("DisbandUnit", "ShowUnitDestination")]
            if actions:
                a = rnd.choice(actions)
                params = {"unitId": unit["id"], "action": a["type"], "title": a.get("title", ""), "confirm": True}
                free_pick = False
                if a.get("target") == "tile":
                    # Some actions offer the tiles to pick; the others (Connect road) take any tile, and saying no to
                    # an unsuitable one is right
                    free_pick = not a.get("targets")
                    tiles = a.get("targets") or card.get("reachable", [])
                    if not tiles:
                        continue
                    t = rnd.choice(tiles)
                    params.update(x=t["x"], y=t["y"])
                self.call("unitAction", expect_fail=free_pick, **params)
        cities = [c for c in state.get("cities", []) if c.get("civ", self.player) == self.player and not c.get("isPuppet")]
        if not cities:
            return
        city = rnd.choice(cities)["name"]
        details = self.call("getCity", name=city)
        if not details:
            return
        choice = rnd.random()
        queue_full = len(details.get("queue", [])) >= 10
        options = [o for o in details.get("options", []) if o.get("buildable") and not queue_full]
        if choice < 0.3 and options:
            pick = rnd.choice(options)
            # As the city board offers them: Build now for anything buildable, Add to queue only when it can queue
            mode = "queue" if pick.get("canQueue") and rnd.random() < 0.5 else "now"
            self.call("setProduction", name=city, construction=pick["name"], mode=mode)
        elif choice < 0.4 and len(details.get("queue", [])) > 1:
            self.call("moveInQueue", name=city, index=len(details["queue"]) - 1, to=rnd.choice(["up", "top"]))
        elif choice < 0.5 and details.get("focusOptions"):
            self.call("setCityFocus", name=city, focus=rnd.choice(details["focusOptions"])["name"])
        elif choice < 0.65 and details.get("tiles"):
            t = rnd.choice(details["tiles"])
            self.call("cityTile", expect_fail=True, name=city, tileX=t["x"], tileY=t["y"], action="toggle")
        elif choice < 0.75 and details.get("specialists"):
            spec = rnd.choice(details["specialists"])
            name = spec.get("name") if isinstance(spec, dict) else spec
            self.call("setSpecialist", expect_fail=True, name=city, specialist=name, delta=rnd.choice([1, -1]))
        elif choice < 0.85:
            buyable = [o for o in details.get("options", []) if o.get("canBuy")]
            if buyable:
                self.call("buyConstruction", name=city, construction=rnd.choice(buyable)["name"])

    def answer_alerts(self, state):
        for alert in state.get("alerts", []):
            if not alert.get("needsAnswer"):
                continue
            options = [o for o in alert.get("options", []) if o.get("enabled", True) and o["id"] not in SKIP_OPTIONS]
            if not options:
                continue
            option = options[(self.turn + len(alert["key"])) % len(options)]
            self.call("answerAlert", key=alert["key"], option=option["id"])

    def clear_blockers(self):
        """Whatever still stops the turn ending after the AI's turn: answer it the way a player would."""
        for _ in range(8):
            status = self.call("getState", since=self.revision) if self.revision is not None else self.call("getState")
            if status is None:
                return False
            blockers = status.get("blockers", [])
            if not blockers:
                return True
            blocker = blockers[0]
            kind = blocker["type"]
            self.blockers_forced[kind] = self.blockers_forced.get(kind, 0) + 1
            if kind == "AnswerAlerts":
                self.answer_alerts(status)
            elif kind in ("PickPolicy", "MoveSpies"):
                self.call("dismissBlocker", type=kind)
            elif kind == "PickTech":
                techs = self.call("getTechs") or {}
                pick = next((t for t in techs.get("techs", []) if t.get("researchable")), None)
                if pick:
                    self.call("setResearch", tech=pick["name"])
            elif kind == "PickConstruction":
                city = blocker.get("city")
                details = self.call("getCity", name=city) or {}
                options = details.get("options", [])
                name = next((o["name"] for o in options if o.get("buildable") and o.get("kind") != "perpetual"), None) \
                    or next((o["name"] for o in options if o.get("buildable")), None)
                self.call("setProduction", name=city, construction=name or "Nothing")
            elif kind == "PickGreatPerson":
                choices = self.call("getGreatPersonChoices") or {}
                pick = next((c for c in choices.get("choices", []) if c.get("available")), None)
                if pick:
                    try:
                        self.calls += 1
                        self.b.call("chooseGreatPerson", unit=pick["name"])
                    except BridgeError as e:   # no space next to the capital: the turn may end, it's kept for later
                        self.notes.append(f"turn {self.turn}: free {pick['name']}: {e}")
            elif kind == "WorldCongressVote":
                choices = self.call("getVoteChoices") or {}
                civs = choices.get("civs", [])
                self.call("vote", civ=civs[0]["name"] if civs else None)
            elif kind in ("FoundPantheon", "ExpandPantheon", "FoundReligion", "EnhanceReligion", "ReformReligion"):
                self.choose_religion()
            elif kind == "NextUnit":
                self.call("setSettings", checkForDueUnits=False)
            else:
                self.call("debug", command="autoPlay")
        self.fail("endTurn", {}, f"still blocked after 8 tries: {blocker.get('type')} ({blocker.get('text')})")
        return False

    def choose_religion(self):
        choices = self.call("getReligionChoices") or {}
        if not choices.get("mode"):
            return
        beliefs = []
        for slot in choices.get("slots", []):   # each slot is a belief type: pick the first free belief of that type
            pick = next((b["name"] for b in choices.get("beliefs", [])
                         if b.get("type") == slot and b.get("available") and b["name"] not in beliefs), None)
            if pick:
                beliefs.append(pick)
        params = {"beliefs": beliefs}
        if choices.get("pickIconAndName"):
            icon = next((i["name"] for i in choices.get("icons", []) if i.get("available")), None)
            if icon:
                params["icon"] = icon
        self.call("chooseReligion", **params)

    def report(self):
        times = [t for _, t in self.turn_times]
        return {
            "game": self.index, "setup": self.setup, "player": self.player, "result": self.result,
            "turns": self.turn, "seconds": round(getattr(self, "seconds", 0), 1), "calls": self.calls,
            "failures": self.failures, "blockersForced": self.blockers_forced, "notes": self.notes,
            "aiTurnSeconds": {
                "average": round(sum(times) / len(times), 2) if times else 0,
                "slowest": sorted([(round(t, 2), turn) for turn, t in self.turn_times], reverse=True)[:5],
            },
            "readSecondsAverage": round(sum(t for _, t in self.read_times) / max(1, len(self.read_times)), 2),
        }


def check_civilopedia(bridge, failures):
    """Every Civilopedia page once (the rules don't change from turn to turn)."""
    try:
        categories = bridge.call("getCivilopedia")
    except BridgeError as e:
        failures.append({"method": "getCivilopedia", "error": str(e)})
        return 0
    pages = 0
    for category in categories.get("categories", []):
        for entry in category.get("entries", []):
            link = f"{category['key']}/{entry['name']}"
            try:
                bridge.call("getCivilopediaEntry", link=link)
                pages += 1
            except BridgeError as e:
                failures.append({"method": "getCivilopediaEntry", "params": {"link": link}, "error": str(e)})
    return pages


def write_report(args, games, pedia_pages, pedia_failures, started):
    data = {"started": time.strftime("%Y-%m-%d %H:%M", time.localtime(started)), "minutes": round((time.time() - started) / 60, 1),
            "civilopediaPages": pedia_pages, "civilopediaFailures": pedia_failures, "games": [g.report() for g in games]}
    with open(os.path.join(args.report, "report.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)
    lines = [f"# Autoplay report - {data['started']}", "",
             f"{len(games)} games in {data['minutes']} minutes. Civilopedia: {pedia_pages} pages read, {len(pedia_failures)} failed.", ""]
    for f in pedia_failures[:20]:
        lines.append(f"- Civilopedia `{f.get('params', {}).get('link', '')}`: {f['error']}")
    total = 0
    for g in data["games"]:
        s = g["setup"]
        lines += ["", f"## Game {g['game']}: {g['player']}, {s.get('mapSize')} map, {s.get('ruleset', 'default rules')}, seed {s.get('seed')}",
                  f"{g['result']} - {g['turns']} turns in {round(g['seconds'] / 60, 1)} minutes, {g['calls']} calls. "
                  f"AI turns: {g['aiTurnSeconds']['average']}s on average, slowest {g['aiTurnSeconds']['slowest'][:3]} (seconds, turn).",
                  ""]
        if g["blockersForced"]:
            lines.append("What the AI left for the player to do, and the script did: " +
                         ", ".join(f"{k} x{v}" for k, v in sorted(g["blockersForced"].items())))
        for note in g["notes"][:10]:
            lines.append(f"- note: {note}")
        if not g["failures"]:
            lines.append("No failures.")
        for f in g["failures"]:
            total += 1
            lines.append(f"- {'**CRASH** ' if f.get('kind') == 'crash' else ''}**turn {f['turn']}** `{f['method']}` {json.dumps(f['params'])[:150]}"
                         + (f" (x{f['count']})" if f["count"] > 1 else "") + f": {f['error'][:300]}"
                         + (f"  \n  save: `{f['save']}`" if f["save"] else ""))
    lines.insert(3, f"**{total} distinct failures** across the games.")
    with open(os.path.join(args.report, "report.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return total


def bridge_log_section(args):
    """The bridge's own complaints (exceptions it caught, alerts it skipped...), counted, added to report.md."""
    counts = {}
    with open(args.bridge_log, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if any(word in line for word in ("Exception", "Error", "Skipping", "Couldn't", "couldn't", "failed")) \
                    and not line.startswith("at ") and "JAVA_TOOL_OPTIONS" not in line:
                counts[line[:200]] = counts.get(line[:200], 0) + 1
    with open(os.path.join(args.report, "report.md"), "a", encoding="utf-8") as f:
        f.write(f"\n## The bridge's own log ({args.bridge_log})\n")
        if not counts:
            f.write("Nothing caught and carried on from.\n")
        for line, n in sorted(counts.items(), key=lambda kv: -kv[1])[:40]:
            f.write(f"- {line}" + (f" (x{n})" if n > 1 else "") + "\n")
    return len(counts)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--games", type=int, default=1)
    parser.add_argument("--seed", type=int, default=None, help="first game's seed (default: random); then +1 per game")
    parser.add_argument("--map-size", default="Tiny", help="Tiny, Small, Medium, Large, Huge (or 'mix')")
    parser.add_argument("--ruleset", default="", help="'Civ V - Vanilla', 'Civ V - Gods & Kings', or 'mix'")
    parser.add_argument("--civ", default="Random")
    parser.add_argument("--ai-players", type=int, default=3)
    parser.add_argument("--city-states", type=int, default=4)
    parser.add_argument("--max-turns", type=int, default=500)
    parser.add_argument("--language", default="", help="play in this language (Unciv's name: German...), or 'mix'")
    parser.add_argument("--tutorials", action="store_true", help="with Unciv's tutorials on")
    parser.add_argument("--max-units", type=int, default=12, help="unit cards read per turn")
    parser.add_argument("--max-cities", type=int, default=8, help="city views read per turn")
    parser.add_argument("--max-saves", type=int, default=10, help="saves kept per game for failures")
    parser.add_argument("--timeout", type=float, default=600, help="seconds to wait for one answer")
    parser.add_argument("--report", default="autoplay-report")
    parser.add_argument("--no-civilopedia", action="store_true")
    parser.add_argument("--bridge-log", default="", help="the bridge's output (e.g. client/logs/bridge.log): its errors "
                        "and warnings - ones it caught and carried on from - go into the report too")
    parser.add_argument("--no-actions", dest="actions", action="store_false",
                        help="only read: no moves, attacks, production changes... of the script's own")
    args = parser.parse_args()
    os.makedirs(args.report, exist_ok=True)

    b = Bridge(args.port, args.timeout)
    ping = b.call("ping")
    print(f"Bridge protocol v{ping['protocol']}, Unciv {ping['uncivVersion']}")
    try:
        b.call("debug", command="nothing")
    except BridgeError as e:
        if "off" in str(e):
            sys.exit("Start the bridge with --debug: the AI plays your turns through a debug command.")
    started = time.time()
    pedia_failures = []
    pedia_pages = 0 if args.no_civilopedia else check_civilopedia(b, pedia_failures)
    seed = args.seed if args.seed is not None else random.randint(1, 10 ** 6)
    sizes = ["Tiny", "Small", "Medium"]
    rulesets = ["Civ V - Gods & Kings", "Civ V - Vanilla"]
    languages = ["English", "German", "French", "Simplified_Chinese", "Russian", "Spanish"]
    nations = {r: [n["name"] for n in b.call("listOptions", ruleset=r)["nations"]] for r in rulesets}
    games = []
    for i in range(1, args.games + 1):
        setup = {"seed": seed + i - 1, "aiPlayers": args.ai_players, "cityStates": args.city_states,
                 "mapSize": sizes[(i - 1) % len(sizes)] if args.map_size == "mix" else args.map_size,
                 "civ": args.civ}
        if args.ruleset == "mix":
            setup["ruleset"] = rulesets[(i - 1) % len(rulesets)]
        elif args.ruleset:
            setup["ruleset"] = args.ruleset
        if args.civ == "Random":   # a different civ each game, one the rules have
            setup["civ"] = random.Random(seed + i).choice(nations.get(setup.get("ruleset", rulesets[0]), nations[rulesets[0]]))
        game_args = argparse.Namespace(**vars(args))
        if args.language == "mix":
            game_args.language = languages[(i - 1) % len(languages)]
        print(f"Game {i}: {setup}" + (f", in {game_args.language}" if game_args.language else ""))
        game = Game(b, game_args, i, setup)
        try:
            game.play()
        except (ConnectionError, socket.timeout, OSError) as e:
            game.result = f"lost the bridge at turn {game.turn}: {e}"
            game.failures.append({"turn": game.turn, "method": "(connection)", "params": {}, "error": str(e), "save": "", "count": 1})
            games.append(game)
            print("  " + game.result)
            break
        except Exception as e:
            game.result = f"script error at turn {game.turn}: {e}"
            traceback.print_exc()
        games.append(game)
        print(f"  {game.result}; {len(game.failures)} distinct failures")
        write_report(args, games, pedia_pages, pedia_failures, started)
    total = write_report(args, games, pedia_pages, pedia_failures, started)
    if args.bridge_log and os.path.exists(args.bridge_log):
        total += bridge_log_section(args)
    print(f"Report: {os.path.join(args.report, 'report.md')} ({total} distinct failures)")
    sys.exit(1 if total or pedia_failures else 0)


if __name__ == "__main__":
    main()
