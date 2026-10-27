package com.jacob.bluey.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

/** The same vectors as laptop/tests/test_secure.py, so both sides provably agree byte for byte. */
class SecureChannelTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val challenge = ByteArray(32) { (it + 32).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun knownVectorsMatchTheLaptop() {
        assertEquals("9efef3c1769e7faaccb1814ab9271f718b1fac238d1181313c19dd5eb7583447",
            Crypto.proof(secret, challenge).hex())
        assertEquals("02ddbdd5dbe21874370bcc91343f6f5fd07482af2a72d762eece136788e296b6",
            Crypto.directionKey(secret, challenge, "mac-to-phone").hex())
        assertEquals("a94673ce0dcc9300b617d16d0d64a131cb4d7bb3f5ceabae9c0aef6de836fc67",
            Crypto.directionKey(secret, challenge, "phone-to-mac").hex())
        assertEquals("630dcd2966c4336691125448", PairingRecord("x", secret).identifier)
    }

    @Test
    fun opensALineSealedByTheLaptop() {
        // laptop.seal({"command": "paired"}, nonce=bytes(12)) from the Python test.
        val line = "AAAAAAAAAAAAAAAAg2F35d+QFO2LAVvhH3ude+KCKcJZyyvQ1dWaoE7FSXFoSMeVWxX0wmo/EOCdXKAlSs7W1GAPaRhqG5vJ"
        val phone = SecureChannel(secret, challenge, server = false)
        assertEquals("paired", phone.open(line).command)
    }

    @Test
    fun roundTripsAndRejectsReplayTamperAndReflection() {
        val laptop = SecureChannel(secret, challenge, server = true)
        val phone = SecureChannel(secret, challenge, server = false)
        val face = FaceState(0.25, -0.5, Mood.HAPPY, 0.0)
        val first = laptop.seal(Packet(face = face))
        assertEquals(face, phone.open(first).face)
        assertThrows(ChannelException::class.java) { phone.open(first) }  // replay
        val second = Base64.getDecoder().decode(laptop.seal(Packet(command = "x")))
        second[second.size - 1] = (second[second.size - 1].toInt() xor 1).toByte()
        assertThrows(ChannelException::class.java) { phone.open(Base64.getEncoder().encodeToString(second)) }
        assertThrows(ChannelException::class.java) { phone.open(phone.seal(Packet(hello = "me"))) }  // own direction
        assertThrows(ChannelException::class.java) { phone.open("not base64 !!") }
        assertEquals("Galaxy", laptop.open(SecureChannel(secret, challenge, server = false).seal(Packet(hello = "Galaxy"))).hello)
    }

    @Test
    fun pairingCodesParseLikeTheLaptopWritesThem() {
        val record = PairingRecord("Galaxy Book", secret, 47474)
        val parsed = PairingRecord.parse(record.qrText)
        assertNotNull(parsed)
        assertEquals("Galaxy Book", parsed!!.service)
        assertEquals(47474, parsed.port)
        assertEquals(secret.toList(), parsed.secret.toList())
        // Exactly what the Python laptop app shows for the same record.
        val python = "bluey-pair:eyJ2ZXJzaW9uIjoxLCJzZXJ2aWNlIjoiR2FsYXh5IEJvb2siLCJzZWNyZXQiOiJBQUVDQXdRRkJnY0lDUW9MREEwT0R4QVJFaE1VRlJZWEdCa2FHeHdkSGg4PSIsInBvcnQiOjQ3NDc0fQ=="
        assertEquals(47474, PairingRecord.parse(python)?.port)
        // The iPhone-era code without a port still parses.
        val swift = "bluey-pair:" + Base64.getEncoder().encodeToString(
            """{"version":1,"service":"Mac","secret":"${Base64.getEncoder().encodeToString(secret)}"}""".toByteArray())
        assertNull(PairingRecord.parse(swift)?.port)
        assertNotNull(PairingRecord.parse(swift))
        for (bad in listOf("", "hello", "bluey-pair:", "bluey-pair:!!!", "bluey-pair:" + "A".repeat(3000))) {
            assertNull(bad, PairingRecord.parse(bad))
        }
    }
}
