package com.unciv.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Unciv's music. Unciv ships no music: its Options > Sound offers to download one track, "Thatched Villagers" by
 * Kevin MacLeod (CC BY 4.0), and players and mods add their own. The client plays the music itself
 * (client/scripts/music.gd); the bridge fetches that track for it. Unciv gets it from the Dropbox it uses for
 * multiplayer; we get the same track from its author's own site instead, so a public build doesn't lean on
 * Unciv's Dropbox (and the Java runtime the client already has does the download).
 */
object Music {
    /** The track on Kevin MacLeod's own site (Unciv's Credits.md credits it to incompetech.com) */
    private const val SOURCE_URL = "https://incompetech.com/music/royalty-free/mp3-royaltyfree/Thatched%20Villagers.mp3"
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
            val connection = URI(SOURCE_URL).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "UncivVR (https://github.com/terdfergeson-design/unciv-vr)")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw Exception("the server answered $status")
            connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (ex: Throwable) {
            part.delete()
            throw BridgeException("Could not download music! (${ex.message ?: ex.javaClass.simpleName})")
        }
        if (part.length() < 100_000) {   // an error page rather than an mp3
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
