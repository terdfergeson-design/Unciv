#!/usr/bin/env python3
"""Smoke test for the Unciv bridge. Needs only the Python standard library.

Start the bridge first (gradlew :bridge:run), then:
    python bridge/smoke_test.py [port]
"""
import json
import os
import socket
import sys
import tempfile

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765


class Bridge:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port))
        self.file = self.sock.makefile("rw", encoding="utf-8", newline="\n")
        self.next_id = 1

    def call(self, method, **params):
        request = {"id": self.next_id, "method": method, "params": params}
        self.next_id += 1
        self.file.write(json.dumps(request) + "\n")
        self.file.flush()
        response = json.loads(self.file.readline())
        if not response["ok"]:
            raise RuntimeError(f"{method} failed: {response['error']}")
        return response["result"]


def main():
    b = Bridge(PORT)
    print("ping:", b.call("ping"))

    options = b.call("listOptions")
    print("nations:", [n["name"] for n in options["nations"]][:6], "...")

    print("setup:", b.call("getSetup"))
    print("newGame:", b.call("newGame", civ="Rome", mapSize="Tiny", aiPlayers=2, cityStates=2, seed=42, remember=False))

    rules = b.call("getRuleset")
    print("terrains:", list(rules["terrains"])[:8], "...")

    state = b.call("getState")
    tiles = state["tiles"]
    explored = [t for t in tiles if t["explored"]]
    print(f"turn {state['turn']} ({state['year']}), {len(tiles)} tiles, {len(explored)} explored")
    for unit in state["units"]:
        print(f"  unit {unit['id']}: {unit['name']} at ({unit['x']},{unit['y']}) moves {unit['movement']}")

    settler = next(u for u in state["units"] if u["name"] == "Settler")
    details = b.call("getUnit", unitId=settler["id"])
    print("settler actions:", [(a["type"], a["enabled"]) for a in details["actions"]])
    print("found city:", b.call("unitAction", unitId=settler["id"], action="FoundCity"))

    warrior = next((u for u in state["units"] if not u["civilian"]), None)
    if warrior:
        details = b.call("getUnit", unitId=warrior["id"])
        target = details["reachable"][-1]
        print("preview move:", b.call("previewMove", unitId=warrior["id"], x=target["x"], y=target["y"]))
        print("move warrior:", b.call("moveUnit", unitId=warrior["id"], x=target["x"], y=target["y"]))

    def clear_blockers():
        """Does what a player would for each thing End turn waits for (protocol v16 blockers)."""
        for _ in range(20):
            state = b.call("getState")
            if not state["blockers"]:
                return
            blocker = state["blockers"][0]
            kind = blocker["type"]
            print("  blocker:", kind, "-", blocker["text"])
            if kind == "AnswerAlerts" or state["alerts"]:
                for alert in state["alerts"]:
                    b.call("answerAlert", key=alert["key"], option=alert["options"][0]["id"])
            elif kind == "PickTech":
                techs = b.call("getTechs")
                b.call("setResearch", tech=next(t["name"] for t in techs["techs"] if t["researchable"]))
            elif kind == "PickConstruction":
                b.call("setProduction", name=blocker["city"], construction="Warrior")
            elif kind in ("PickPolicy", "MoveSpies"):
                b.call("dismissBlocker", type=kind)
            elif kind == "PickGreatPerson":
                choices = b.call("getGreatPersonChoices")
                b.call("chooseGreatPerson", unit=next(c["name"] for c in choices["choices"] if c["available"]))
            elif kind == "WorldCongressVote":
                b.call("vote")
            elif kind in ("FoundPantheon", "ExpandPantheon", "FoundReligion", "EnhanceReligion", "ReformReligion"):
                choices = b.call("getReligionChoices")
                picks = []
                for slot in choices["slots"]:
                    picks.append(next(x["name"] for x in choices["beliefs"] if x["available"] and x["name"] not in picks
                                      and (x["type"] == slot or slot == "Any")))
                icon = next((i["name"] for i in choices.get("icons", []) if i["available"]), None)
                b.call("chooseReligion", beliefs=picks, icon=icon, name=icon)
            else:
                raise RuntimeError(f"don't know how to clear {kind}")
        raise RuntimeError("blockers didn't clear")

    print("settings:", b.call("setSettings", checkForDueUnits=False))
    for _ in range(5):
        clear_blockers()
        result = b.call("endTurn")
        print(f"endTurn -> turn {result['turn']}, your turn: {result['yourTurn']}, "
              f"{len(result['notifications'])} notifications")

    state = b.call("getState")
    print("cities:", state["cities"])
    if state["cities"]:
        city = state["cities"][0]["name"]
        detail = b.call("getCity", name=city)
        print(f"city view: focus {detail['focus']}, free pop {detail['freePopulation']}, "
              f"{len(detail['tiles'])} tiles, {len(detail['specialists'])} specialist kinds, stats {detail['stats']}")
        worked = next((t for t in detail["tiles"] if t["state"] == "worked"), None)
        if worked:
            after = b.call("cityTile", name=city, tileX=worked["x"], tileY=worked["y"], action="toggle")
            print("unworked a tile, free pop now", after["freePopulation"])
            after = b.call("cityTile", name=city, tileX=worked["x"], tileY=worked["y"], action="lock")
            print("locked it:", next(t["state"] for t in after["tiles"] if (t["x"], t["y"]) == (worked["x"], worked["y"])))
        print("focus:", b.call("setCityFocus", name=city, focus="ProductionFocus")["focus"])
        print("describe:", b.call("describeConstruction", name=city, construction="Monument")["description"][:80])
    delta = b.call("getState", since=state["revision"])
    print(f"delta getState: full={delta['full']}, {len(delta['tiles'])} of {len(state['tiles'])} tiles resent")
    for tab in ["cities", "stats", "trades", "units", "politics", "resources", "religion", "wonders", "notifications"]:
        o = b.call("getOverview", tab=tab)
        print(f"overview {tab}: tabs {[t['id'] + ':' + t['state'] for t in o['tabs']][:3]}..., keys {sorted(k for k in o if k not in ('tabs', 'tab', 'turn'))}")
    print("victory:", [v["name"] for v in b.call("getVictory")["ourStatus"]])
    print("religion:", b.call("getReligionChoices")["state"])
    print("espionage:", b.call("getEspionage")["enabled"])
    print("great people:", len(b.call("getGreatPersonChoices")["choices"]))
    print("vote:", b.call("getVoteChoices")["canVote"])
    some_unit = b.call("getState")["units"][0]
    print("disband asks first:", b.call("unitAction", unitId=some_unit["id"], action="DisbandUnit").get("confirm"))
    save_path = os.path.join(tempfile.gettempdir(), "unciv-bridge-smoke-test.save")
    print("saved:", b.call("saveGame", path=save_path))
    print("loaded:", b.call("loadGame", path=save_path))
    print("saves:", b.call("listSaves"))
    print("\nAll good.")


if __name__ == "__main__":
    main()
