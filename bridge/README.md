# unciv-bridge

A headless Unciv game engine for other frontends (the Unciv VR client) to drive.
It runs the real game logic (rules, AI and turn processing) with no window,
and answers JSON requests on `127.0.0.1:8765`. See [PROTOCOL.md](PROTOCOL.md).

## Run
From the repository root (needs JDK 21):

```
gradlew :bridge:run                      # Windows: gradlew.bat :bridge:run
gradlew :bridge:run --args="--port 9000 --verbose"
```

The first build downloads Gradle and every dependency, so it takes a few minutes.
Wait for `Unciv bridge listening on 127.0.0.1:8765`.

## Test it without Godot
With the bridge running, in a second terminal:

```
python bridge/smoke_test.py
```

This starts a game as Rome, founds a city, moves a warrior, plays 5 turns, then saves and reloads.

## Playtest whole games with nobody playing
`autoplay_test.py` plays complete games where Unciv's AI plays every civilization, yours included (Unciv's AutoPlay).
Every turn it also asks the bridge everything the VR client would ask, does a few random things a player would do
(moves, attacks, promotions, production changes, buying...), and answers the messages. Anything that crashes, or
anything the bridge offered and then refused, is reported, with the game saved just before it so it can be
reproduced. Start the bridge with debug commands on, then:

```
gradlew :bridge:run --args="--debug --port 8799"
python bridge/autoplay_test.py --port 8799 --games 6 --map-size mix --ruleset mix --language mix
```

Other options: `--max-turns`, `--seed`, `--civ`, `--ai-players`, `--city-states`, `--tutorials`, `--no-actions`
(only read), `--bridge-log <file>` (also lists the errors the bridge caught and carried on from). The report goes to
`autoplay-report/report.md` (and `report.json`). A Tiny or Small game to the end takes well under a minute.

## Standalone jar
`gradlew :bridge:dist` → `bridge/build/libs/UncivBridge.jar`. Run it from `android/assets`
(Unciv reads its rulesets relative to the working directory):

```
cd android/assets
java -jar ../../bridge/build/libs/UncivBridge.jar
```

## Design notes
- Single-threaded game access: requests are processed one at a time under a lock.
- Reads go through Unciv's View API (`com.unciv.view`, upstream issue #15280), so the client only
  sees what the human player is allowed to see, and our code stays close to where upstream is heading.
- Only `settings.gradle.kts` (one word) is changed outside this folder, so pulling upstream
  updates should stay painless.
