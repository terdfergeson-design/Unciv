package com.unciv.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Line-delimited JSON over TCP, bound to localhost only.
 *
 * Request:   {"id": 1, "method": "newGame", "params": {...}}
 * Response:  {"id": 1, "ok": true,  "result": {...}}
 *            {"id": 1, "ok": false, "error": "message"}
 *
 * Requests are processed one at a time, in order. Several clients may connect
 * (e.g. Godot plus a debugging script); they share the same game.
 */
class BridgeServer(private val port: Int, private val session: GameSession) {
    private val gameLock = Any()
    @Volatile private var shutdownRequested = false

    fun run() {
        val serverSocket = ServerSocket(port, 4, InetAddress.getLoopbackAddress())
        println("Unciv bridge listening on 127.0.0.1:$port (protocol v${BridgeMain.PROTOCOL_VERSION})")
        while (true) {
            val socket = serverSocket.accept()
            thread(name = "bridge-client-${socket.port}", isDaemon = true) { handleClient(socket) }
        }
    }

    private fun handleClient(socket: Socket) {
        println("Client connected from port ${socket.port}")
        socket.tcpNoDelay = true
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val response = handleLine(line)
                writer.write(response.toString())
                writer.write("\n")
                writer.flush()
                if (shutdownRequested) {
                    println("Shutdown requested by client - exiting")
                    exitProcess(0)
                }
            }
        } catch (_: SocketException) {
            // client went away
        } finally {
            socket.close()
            println("Client from port ${socket.port} disconnected")
        }
    }

    private fun handleLine(line: String): JsonObject {
        var id: JsonElement = JsonNull
        return try {
            val request = Json.parseToJsonElement(line).jsonObject
            id = request["id"] ?: id
            val method = request.str("method") ?: throw BridgeException("Missing 'method'")
            val params = request.obj("params") ?: JsonObject(emptyMap())
            if (method == "shutdown") {
                // Used by the Godot client to stop a bridge it launched itself. Answer first, then exit.
                shutdownRequested = true
                return buildJsonObject {
                    put("id", id)
                    put("ok", true)
                    put("result", buildJsonObject { put("shuttingDown", true) })
                }
            }
            val startMs = System.currentTimeMillis()
            val result = synchronized(gameLock) { session.handle(method, params) }
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed > 200) println("$method took ${elapsed}ms")
            buildJsonObject {
                put("id", id)
                put("ok", true)
                put("result", result)
            }
        } catch (ex: Throwable) {
            if (ex !is BridgeException) {
                System.err.println("Error handling request: $line")
                ex.printStackTrace()
            }
            buildJsonObject {
                put("id", id)
                put("ok", false)
                put("error", ex.message ?: ex.javaClass.simpleName)
            }
        }
    }
}

/** An expected, user-facing error (bad parameters, illegal move...). Reported without a stack trace. */
class BridgeException(message: String) : Exception(message)
