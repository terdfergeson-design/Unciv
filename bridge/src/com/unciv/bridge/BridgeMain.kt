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
    const val PROTOCOL_VERSION = 6

    @JvmStatic
    fun main(args: Array<String>) {
        val portIndex = args.indexOf("--port")
        val port = if (portIndex >= 0 && portIndex + 1 < args.size) args[portIndex + 1].toInt() else 8765
        val verbose = "--verbose" in args

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

        val session = GameSession()
        BridgeServer(port, session).run()
    }

    /** Same minimal setup as Unciv's own ConsoleLauncher / unit tests: no window, no GL, no Gdx.app. */
    private fun initUnciv() {
        if (Gdx.files == null) Gdx.files = HeadlessFiles()
        val game = UncivGame(true) // console mode: rulesets are read straight from the working directory
        UncivGame.Current = game
        game.settings = GameSettings().apply {
            showTutorials = false
            turnsBetweenAutosaves = 10000
            // Exploring / automated units and multi-turn moves act when you end your turn (see GameSession.endTurn),
            // not at the start of it - Unciv's "Move automated units" step, done for you.
            automatedUnitsMoveOnTurnStart = false
        }
        val startMs = System.currentTimeMillis()
        RulesetCache.loadRulesets(consoleMode = true, noMods = true)
        println("Loaded rulesets ${RulesetCache.keys.sorted()} in ${System.currentTimeMillis() - startMs}ms")
    }
}
