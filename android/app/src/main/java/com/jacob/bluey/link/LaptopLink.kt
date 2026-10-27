package com.jacob.bluey.link

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

/**
 * Keeps one encrypted connection to the laptop, the phone's side of Shared/GooglyLink.swift.
 * Over USB the laptop is at localhost: `adb reverse` carries the connection through the cable.
 *
 * Runs on its own thread and reconnects every two seconds until [stop]. Every callback goes
 * through [post] (the main thread in the app), and callbacks from an old connection are dropped.
 */
class LaptopLink(
    private val post: (Runnable) -> Unit,
    private val listener: Listener,
    /** Shown on the laptop, e.g. "Samsung SM-S921U". */
    private val deviceName: String = "Android phone",
    private val host: String = "127.0.0.1",
    private val retryMillis: Long = 2000,
) {
    sealed class Status {
        data object Connecting : Status()
        data class Connected(val laptop: String?) : Status()
        data class Waiting(val reason: String) : Status()
    }

    interface Listener {
        fun onStatus(status: Status)
        fun onPacket(packet: Packet)
    }

    private val lock = Object()
    private var generation = 0
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var channel: SecureChannel? = null

    fun start(record: PairingRecord) {
        val mine = synchronized(lock) {
            generation++
            closeLocked()
            generation
        }
        Thread({ run(record, mine) }, "bluey-link").apply { isDaemon = true }.start()
    }

    fun stop() {
        synchronized(lock) {
            generation++
            closeLocked()
        }
    }

    /** Sends a packet if connected and paired. Safe from any thread. */
    fun send(packet: Packet) {
        synchronized(lock) {
            val out = output ?: return
            val channel = channel ?: return
            try {
                out.write((channel.seal(packet) + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (error: IOException) {
                closeLocked()
            }
        }
    }

    private fun current(mine: Int) = synchronized(lock) { generation == mine }

    private fun emit(mine: Int, action: () -> Unit) = post(Runnable { if (current(mine)) action() })

    private fun closeLocked() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
        channel = null
    }

    private fun run(record: PairingRecord, mine: Int) {
        val port = record.port ?: Protocol.DEFAULT_PORT
        while (current(mine)) {
            emit(mine) { listener.onStatus(Status.Connecting) }
            val reason = try {
                session(record, port, mine)
                "The laptop disconnected."
            } catch (error: ConnectException) {
                "Nothing on port $port. Plug in the USB cable and start the laptop app."
            } catch (error: EOFException) {
                // adb accepts on the phone even when nothing answers on the laptop, then hangs up.
                "The USB link is up, but the laptop app didn't answer. Is it running?"
            } catch (error: SocketTimeoutException) {
                "The laptop stopped responding."
            } catch (error: ChannelException) {
                "The laptop refused this pairing code. Scan its QR code again."
            } catch (error: Exception) {
                error.message ?: error.javaClass.simpleName
            }
            synchronized(lock) { if (generation == mine) closeLocked() }
            emit(mine) { listener.onStatus(Status.Waiting(reason)) }
            try {
                Thread.sleep(retryMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun session(record: PairingRecord, port: Int, mine: Int) {
        val socket = Socket()
        synchronized(lock) {
            if (generation != mine) return
            this.socket = socket
        }
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(host, port), 3000)
        socket.soTimeout = 8000 // the handshake must finish quickly
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream()

        val hello = JSONObject(readLine(input))
        val challenge = Base64.getDecoder().decode(hello.getString("challenge"))
        if (challenge.size != 32) throw ChannelException("bad challenge")
        val proof = JSONObject().put("proof", Base64.getEncoder().encodeToString(Crypto.proof(record.secret, challenge)))
        out.write((proof.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        val channel = SecureChannel(record.secret, challenge, server = false)
        val paired = try {
            channel.open(readLine(input))
        } catch (error: EOFException) {
            throw ChannelException("closed after proof") // the laptop drops a wrong proof
        }
        if (paired.command != "paired") throw ChannelException("expected 'paired'")

        synchronized(lock) {
            if (generation != mine) return
            this.output = out
            this.channel = channel
        }
        send(Packet(hello = deviceName))
        // The laptop resends the face at least once a second, so silence means it's gone.
        socket.soTimeout = 6000
        while (current(mine)) {
            val packet = channel.open(readLine(input))
            if (packet.hello != null) emit(mine) { listener.onStatus(Status.Connected(packet.hello)) }
            emit(mine) { listener.onPacket(packet) }
        }
    }

    companion object {
        /** Reads one newline-terminated line, refusing lines over [Protocol.MAX_LINE]. */
        fun readLine(input: InputStream): String {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte == -1) throw EOFException()
                if (byte == '\n'.code) return buffer.toString("UTF-8")
                if (buffer.size() >= Protocol.MAX_LINE) throw IOException("line too long")
                buffer.write(byte)
            }
        }
    }
}
