package com.jacob.bluey.link

import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing and the encrypted channel: a byte-for-byte port of Shared/SecurePairing.swift
 * (and laptop/bluey_laptop/secure.py).
 *
 * - Proof: HMAC-SHA256(key = secret, "bluey-pair-v1" + challenge).
 * - Keys: HKDF-SHA256(secret, salt = challenge, info = "bluey-v1-mac-to-phone" / "bluey-v1-phone-to-mac").
 * - Each line: base64(nonce(12) + ciphertext + tag(16)) of JSON {"sequence": n, "packet": {...}},
 *   CryptoKit's `AES.GCM.SealedBox.combined` layout. Java's GCM output lacks the nonce, so it's prepended here.
 */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var block = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            block = hmacSha256(prk, block + info + byteArrayOf(counter.toByte()))
            val take = minOf(block.size, length - filled)
            System.arraycopy(block, 0, out, filled, take)
            filled += take
            counter++
        }
        return out
    }

    fun proof(secret: ByteArray, challenge: ByteArray) =
        hmacSha256(secret, "bluey-pair-v1".toByteArray() + challenge)

    fun directionKey(secret: ByteArray, challenge: ByteArray, direction: String) =
        hkdf(secret, challenge, "bluey-v1-$direction".toByteArray(), 32)
}

class ChannelException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SecureChannel(secret: ByteArray, challenge: ByteArray, server: Boolean) {
    private val sendKey = Crypto.directionKey(secret, challenge, if (server) "mac-to-phone" else "phone-to-mac")
    private val receiveKey = Crypto.directionKey(secret, challenge, if (server) "phone-to-mac" else "mac-to-phone")
    private var sent = 0L
    private var received = 0L

    /** One line, without the newline. [nonce] is only for test vectors. */
    @Synchronized
    fun seal(packet: Packet, nonce: ByteArray = Crypto.randomBytes(12)): String {
        val record = JSONObject().put("sequence", sent).put("packet", packet.toJson())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce))
        val sealed = nonce + cipher.doFinal(record.toString().toByteArray(Charsets.UTF_8))
        sent++
        return Base64.getEncoder().encodeToString(sealed)
    }

    @Synchronized
    fun open(line: String): Packet {
        val record = try {
            val sealed = Base64.getDecoder().decode(line.trim())
            if (sealed.size < 12 + 16) throw ChannelException("too short")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"),
                GCMParameterSpec(128, sealed, 0, 12))
            JSONObject(String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8))
        } catch (error: ChannelException) {
            throw error
        } catch (error: Exception) { // bad base64, failed authentication or bad JSON
            throw ChannelException("invalid record", error)
        }
        val packet = record.optJSONObject("packet")
        if (!record.has("sequence") || record.optLong("sequence", -1) != received || packet == null) {
            throw ChannelException("out of order or malformed record")
        }
        received++
        return Packet.fromJson(packet)
    }
}

/** The QR code's contents: "bluey-pair:" + base64(JSON {"version":1,"service":...,"secret":...,"port"?}). */
class PairingRecord(val service: String, val secret: ByteArray, val port: Int? = null) {
    /** Public fingerprint (the Bonjour TXT `bluey-id`), handy for telling pairings apart. */
    val identifier: String
        get() = MessageDigest.getInstance("SHA-256").digest(secret).copyOf(12)
            .joinToString("") { "%02x".format(it) }

    val qrText: String
        get() {
            val json = JSONObject().put("version", 1).put("service", service)
                .put("secret", Base64.getEncoder().encodeToString(secret))
            port?.let { json.put("port", it) }
            return PREFIX + Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
        }

    companion object {
        const val PREFIX = "bluey-pair:"

        fun parse(text: String?): PairingRecord? {
            if (text == null || !text.startsWith(PREFIX) || text.length >= 2048) return null
            return try {
                val json = JSONObject(String(Base64.getDecoder().decode(text.removePrefix(PREFIX)), Charsets.UTF_8))
                val service = json.optString("service")
                val secret = Base64.getDecoder().decode(json.optString("secret"))
                val port = if (json.has("port")) json.getInt("port") else null
                when {
                    json.optInt("version") != 1 || service.isEmpty() || secret.size != 32 -> null
                    port != null && port !in 1..65535 -> null
                    else -> PairingRecord(service, secret, port)
                }
            } catch (error: IllegalArgumentException) {
                null
            } catch (error: JSONException) {
                null
            }
        }
    }
}
