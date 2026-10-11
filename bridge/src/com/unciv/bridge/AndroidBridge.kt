package com.unciv.bridge

import com.badlogic.gdx.Files
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessFiles
import com.badlogic.gdx.files.FileHandle
import java.io.File
import kotlin.concurrent.thread

/**
 * The bridge inside the standalone Quest app: the Godot client's UncivBridge Android plugin (bridge-android) calls
 * [start], and the bridge then listens on 127.0.0.1 exactly as the PC's does, so the client talks to it the same way.
 *
 * Android can't run the bridge from Unciv's assets folder the way the PC does (there's no working directory to
 * change to), so Unciv's "internal" files are pointed at the folder the app unpacked them into, and the bridge's own
 * folder (~/.unciv-vr: saves, setup, tutorials) at the app's storage.
 */
object AndroidBridge {
    @Volatile var started = false
        private set
    @Volatile var lastError = ""
        private set
    /** What the bridge is doing (shown while the client waits): "loading Unciv's rules", "listening"... */
    @Volatile var stage = ""
        private set

    /** Unciv's internal files from [assetsDir] (android/assets: jsons, sounds...); everything else as on the PC. */
    private class AssetFiles(private val assetsDir: String, private val base: Files = HeadlessFiles()) : Files by base {
        override fun getFileHandle(path: String, type: Files.FileType): FileHandle =
            if (type == Files.FileType.Internal) internal(path) else base.getFileHandle(path, type)
        override fun internal(path: String): FileHandle = FileHandle(File(assetsDir, path))
    }

    /**
     * Starts the bridge on a background thread. [assetsDir]: the unpacked android/assets folder; [homeDir]: where
     * .unciv-vr goes. Returns false (see [lastError]) if it couldn't; the rulesets load on the bridge's thread, and the
     * client simply waits for the port to open.
     */
    @JvmStatic
    fun start(assetsDir: String, homeDir: String, port: Int): Boolean {
        if (started) return true
        if (!File(assetsDir, "jsons").isDirectory) {
            lastError = "No Unciv rules in $assetsDir/jsons"
            return false
        }
        started = true
        System.setProperty("user.home", homeDir)   // BridgeMain.dataDir: ~/.unciv-vr in the app's storage
        thread(name = "unciv-bridge", isDaemon = true) {
            try {
                stage = "setting up files"
                Gdx.files = AssetFiles(assetsDir)
                stage = "loading Unciv's rules"
                BridgeMain.initUnciv(rulesetsFromWorkingDir = false)
                stage = "loading the tutorials"
                Tutorials.load()
                stage = "opening port $port"
                val server = BridgeServer(port, GameSession())
                stage = "listening"
                server.run()
                stage = "stopped"
            } catch (ex: Throwable) {
                // The client shows this in the headset: what went wrong and where
                lastError = "$ex (while $stage)\n" + ex.stackTrace.take(8).joinToString("\n") { "  at $it" } +
                    (ex.cause?.let { "\ncaused by $it" } ?: "")
                System.err.println("Unciv bridge stopped: $ex")
                ex.printStackTrace()
                started = false
            }
        }
        return true
    }
}
