package com.unciv.bridge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Lenient parameter accessors: Godot's JSON encoder sends every number as a float ("3.0"), so accept that too.

internal fun JsonObject.primitive(key: String): JsonPrimitive? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element as? JsonPrimitive ?: throw BridgeException("Parameter '$key' must be a plain value")
}

internal fun JsonObject.str(key: String): String? = primitive(key)?.contentOrNull

internal fun JsonObject.long(key: String): Long? {
    val p = primitive(key) ?: return null
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: throw BridgeException("Parameter '$key' must be a number")
}

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? {
    val p = primitive(key) ?: return null
    return p.booleanOrNull ?: throw BridgeException("Parameter '$key' must be true or false")
}

internal fun JsonObject.obj(key: String): JsonObject? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element as? JsonObject ?: throw BridgeException("Parameter '$key' must be an object")
}

internal fun JsonObject.requireInt(key: String): Int = int(key) ?: throw BridgeException("Missing parameter '$key'")
internal fun JsonObject.requireStr(key: String): String = str(key) ?: throw BridgeException("Missing parameter '$key'")
