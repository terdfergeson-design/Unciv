package com.unciv.bridge

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.unciv.UncivGame
import com.unciv.models.metadata.GameSettings
import com.unciv.models.ruleset.RulesetCache
import com.unciv.utils.DefaultLogBackend
import com.unciv.utils.Log
import java.io.File

/**
 * Entry point for the headless Unciv bridge.
 *
 * Usage: run from the `android/assets` folder (Unciv loads rulesets relative to the working directory):
 *     java -jar UncivBridge.jar [--port 8765] [--verbose]
 * or simply `gradlew :bridge:run` from the repository root.
 */
object BridgeMain {
    const val PROTOCOL_VERSION = 23

    /** The bridge's own folder (saves, the last game setup): ~/.unciv-vr */
    val dataDir = File(System.getProperty("user.home"), ".unciv-vr")
    val savesDir = File(dataDir, "SaveFiles")
    val lastSetupFile = File(dataDir, "last-setup.json")

    @JvmStatic
    fun main(args: Array<String>) {
        val portIndex = args.indexOf("--port")
        val port = if (portIndex >= 0 && portIndex + 1 < args.size) args[portIndex + 1].toInt() else 8765
        val verbose = "--verbose" in args
        DebugCommands.enabled = "--debug" in args

        // Quiet Unciv's debug logging unless asked for it
        Log.backend = object : DefaultLogBackend() {
            override fun isRelease() = !verbose
        }

        if (!File("jsons").isDirectory) {
            System.err.println("Can't find the 'jsons' folder - the bridge must be started with android/assets as its working directory.")
            System.err.println("Current directory: ${File(".").absoluteFile.normalize()}")
            System.exit(1)
        }

        initUnciv()
        Tutorials.load()

        val session = GameSession()
        // --exit-when-idle N: quit once no client has been connected for N seconds. The Godot client launches its
        // bridge with this, so a bridge orphaned by a crashed or force-stopped client doesn't keep running forever
        // (and keep serving old code after an update).
        val idleIndex = args.indexOf("--exit-when-idle")
        val idleSeconds = if (idleIndex >= 0 && idleIndex + 1 < args.size) args[idleIndex + 1].toIntOrNull() ?: 0 else 0
        BridgeServer(port, session, idleSeconds).run()
    }

    /**
     * Same minimal setup as Unciv's own ConsoleLauncher / unit tests: no window, no GL, no Gdx.app.
     * [rulesetsFromWorkingDir]: read the rulesets from ./jsons (the PC, run from android/assets); false reads them
     * through Gdx.files.internal instead (the Quest app, where AndroidBridge points that at the unpacked assets).
     */
    internal fun initUnciv(rulesetsFromWorkingDir: Boolean = true) {
        if (Gdx.files == null) Gdx.files = HeadlessFiles()
        val game = UncivGame(true) // console mode: rulesets are read straight from the working directory
        UncivGame.Current = game
        // Some game events look up their sound file (ancient ruins rewards, for one); without this they'd throw.
        // There's no audio here, so loading the sound itself then quietly fails, as Unciv expects when a file is missing.
        game.files = com.unciv.logic.files.UncivFiles(Gdx.files)
        game.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
            // Exploring / automated units and multi-turn moves act when you end your turn (see GameSession.endTurn),
            // not at the start of it - Unciv's "Move automated units" step, done for you.
            automatedUnitsMoveOnTurnStart = false
        }
        val startMs = System.currentTimeMillis()
        RulesetCache.loadRulesets(consoleMode = rulesetsFromWorkingDir, noMods = true)
        println("Loaded rulesets ${RulesetCache.keys.sorted()} in ${System.currentTimeMillis() - startMs}ms")
    }
}
