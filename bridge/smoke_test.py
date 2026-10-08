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

    print("newGame:", b.call("newGame", civ="Rome", mapSize="Tiny", aiPlayers=2, cityStates=2, seed=42))

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
        print("move warrior:", b.call("moveUnit", unitId=warrior["id"], x=target["x"], y=target["y"]))

    for _ in range(5):
        result = b.call("endTurn")
        print(f"endTurn -> turn {result['turn']}, your turn: {result['yourTurn']}, "
              f"{len(result['notifications'])} notifications")

    state = b.call("getState")
    print("cities:", state["cities"])
    save_path = os.path.join(tempfile.gettempdir(), "unciv-bridge-smoke-test.save")
    print("saved:", b.call("saveGame", path=save_path))
    print("loaded:", b.call("loadGame", path=save_path))
    print("\nAll good.")


if __name__ == "__main__":
    main()
