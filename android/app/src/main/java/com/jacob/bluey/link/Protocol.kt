package com.jacob.bluey.link

import org.json.JSONObject

/** What the phone and laptop agree on. Mirrors Shared/GooglyLink.swift and laptop/bluey_laptop/protocol.py. */
object Protocol {
    /** Over USB, `adb reverse tcp:47474 tcp:47474` makes this phone's localhost:47474 reach the laptop. */
    const val DEFAULT_PORT = 47474
    /** Same cap as the Swift LineConnection. */
    const val MAX_LINE = 16 * 1024 * 1024
}

enum class Mood(val wire: String) {
    LISTENING("listening"), RESTING("resting"), THINKING("thinking"), TALKING("talking"),
    POINTING("pointing"), HAPPY("happy"), SLEEPY("sleepy");

    companion object {
        fun from(wire: String?): Mood? = entries.firstOrNull { it.wire == wire }
    }
}

/** Where the eyes look (x: -1 left … 1 right, y: -1 up … 1 down), the mood, and voice level 0…1. */
data class FaceState(
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val mood: Mood = Mood.LISTENING,
    val talk: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("gazeX", gazeX).put("gazeY", gazeY).put("mood", mood.wire).put("talk", talk)

    companion object {
        fun fromJson(json: JSONObject) = FaceState(
            gazeX = json.optDouble("gazeX", 0.0).finiteOr(0.0),
            gazeY = json.optDouble("gazeY", 0.0).finiteOr(0.0),
            mood = Mood.from(json.optString("mood")) ?: Mood.LISTENING,
            talk = json.optDouble("talk", 0.0).finiteOr(0.0),
        )

        private fun Double.finiteOr(fallback: Double) = if (isFinite()) this else fallback
    }
}

/** Swift's `Packet`: every field optional, and only set fields go on the wire. */
data class Packet(
    val face: FaceState? = null,
    val hello: String? = null,
    val volume: Double? = null,
    val command: String? = null,
    val audio: String? = null,
    val speech: Int? = null,
    val callID: String? = null,
    val tool: String? = null,
    val text: String? = null,
    val image: String? = null,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        face?.let { json.put("face", it.toJson()) }
        hello?.let { json.put("hello", it) }
        volume?.let { json.put("volume", it) }
        command?.let { json.put("command", it) }
        audio?.let { json.put("audio", it) }
        speech?.let { json.put("speech", it) }
        callID?.let { json.put("callID", it) }
        tool?.let { json.put("tool", it) }
        text?.let { json.put("text", it) }
        image?.let { json.put("image", it) }
        return json
    }

    companion object {
        fun fromJson(json: JSONObject) = Packet(
            face = json.optJSONObject("face")?.let(FaceState::fromJson),
            hello = json.stringOrNull("hello"),
            volume = if (json.has("volume")) json.optDouble("volume") else null,
            command = json.stringOrNull("command"),
            audio = json.stringOrNull("audio"),
            speech = if (json.has("speech")) json.optInt("speech") else null,
            callID = json.stringOrNull("callID"),
            tool = json.stringOrNull("tool"),
            text = json.stringOrNull("text"),
            image = json.stringOrNull("image"),
        )

        private fun JSONObject.stringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) optString(key) else null
    }
}
