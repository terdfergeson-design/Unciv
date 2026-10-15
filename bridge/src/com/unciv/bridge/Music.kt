package com.unciv.bridge

import com.unciv.logic.multiplayer.storage.DropBox
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Unciv's music. Unciv ships no music: its Options > Sound offers to download one track, "Thatched Villagers",
 * from the same Dropbox it uses for multiplayer, and players and mods add their own. The client plays the music
 * itself (client/scripts/music.gd); the bridge only fetches that track, since it already speaks Unciv's Dropbox.
 */
object Music {
    /** Where Unciv's "Download music" button gets it (MusicController.musicFallbackLocation) */
    private const val REMOTE_PATH = "/music/thatched-villagers.mp3"
    /** The name Unciv saves it under (MusicController.musicFallbackLocalName): an Ambient track */
    const val DEFAULT_FILE = "Thatched Villagers - Ambient.mp3"

    /** ~/.unciv-vr/Music, where the client looks for music */
    val folder get() = File(BridgeMain.dataDir, "Music")

    /** Downloads Unciv's default track into [folder] (replacing it if it's there already). */
    fun downloadDefault(): JsonElement {
        folder.mkdirs()
        val target = File(folder, DEFAULT_FILE)
        val part = File(folder, "$DEFAULT_FILE.part")
        try {
            DropBox.downloadFile(REMOTE_PATH).use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (ex: Throwable) {
            part.delete()
            throw BridgeException("Could not download music! (${ex.message ?: ex.javaClass.simpleName})")
        }
        if (part.length() < 10_000) {   // an error page rather than an mp3
            part.delete()
            throw BridgeException("Could not download music! (the download was empty)")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return buildJsonObject {
            put("path", target.absolutePath.replace('\\', '/'))
            put("bytes", target.length())
        }
    }
}
