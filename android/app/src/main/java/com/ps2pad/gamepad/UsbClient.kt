package com.ps2pad.gamepad

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * USB (wired) transport to the PC server.
 *
 * Unlike [NetworkClient]'s UDP-over-Wi-Fi, this streams the same packets over a
 * TCP socket to **127.0.0.1**. On the phone that loopback connection is tunnelled
 * to the PC by `adb reverse tcp:9999 tcp:9999` (which the PC server sets up
 * automatically), so the bytes travel down the USB cable instead of the network.
 * `adb` only forwards TCP, which is why this path is TCP while Wi-Fi stays UDP.
 *
 *  - [enabled] gates the worker: flip it on when the user taps USB.
 *  - A single worker thread keeps the socket connected (retrying on failure) and
 *    re-sends the latest packet ~30x/sec, mirroring [NetworkClient] so a dropped
 *    write can't leave a button stuck.
 *  - [connected] lets the UI show whether the cable link is live.
 */
class UsbClient {

    private val lastPacket = AtomicReference<ByteArray?>(null)
    private val sock = AtomicReference<Socket?>(null)

    @Volatile var enabled = false
        private set
    @Volatile var connected = false
        private set

    @Volatile private var running = true

    init {
        Thread({ loop() }, "ps2pad-usb").apply { isDaemon = true; start() }
    }

    /** Start streaming over USB (opens/keeps the loopback TCP connection). */
    fun enable() { enabled = true }

    /** Stop streaming and drop the connection (Wi-Fi takes over again). */
    fun disable() {
        enabled = false
        closeSocket()
    }

    /** Queue a packet; sent immediately if the cable link is up. */
    fun send(packet: ByteArray) {
        lastPacket.set(packet)
        if (!enabled) return
        writePacket(packet)
    }

    private fun loop() {
        while (running) {
            if (!enabled) {
                sleep(200)
                continue
            }
            if (sock.get() == null && !openSocket()) {
                sleep(1000)            // adb reverse / server not ready yet — retry
                continue
            }
            lastPacket.get()?.let { writePacket(it) }
            sleep(33)                  // ~30 Hz keep-alive / anti-stuck resend
        }
    }

    private fun openSocket(): Boolean {
        return runCatching {
            val s = Socket()
            s.tcpNoDelay = true        // input is latency-sensitive; don't coalesce
            s.connect(InetSocketAddress("127.0.0.1", NetworkClient.PORT), 1000)
            sock.set(s)
            connected = true
        }.isSuccess
    }

    private fun writePacket(packet: ByteArray) {
        val s = sock.get() ?: return
        runCatching {
            s.getOutputStream().write(packet)
            s.getOutputStream().flush()
        }.onFailure {
            closeSocket()              // worker will reconnect on the next tick
        }
    }

    private fun closeSocket() {
        connected = false
        sock.getAndSet(null)?.let { runCatching { it.close() } }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false }
    }

    fun close() {
        running = false
        closeSocket()
    }
}
