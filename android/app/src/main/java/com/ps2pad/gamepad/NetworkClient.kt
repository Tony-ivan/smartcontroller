package com.ps2pad.gamepad

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * UDP transport to the PC server.
 *
 *  - [send] fires the latest input packet (non-blocking, on a worker thread).
 *  - A repeat thread re-sends the last packet ~30x/sec so dropped UDP packets
 *    can't leave a button stuck on the PC side.
 *  - [discover] broadcasts on the subnet and waits for the server's reply so
 *    the app learns the PC's IP automatically.
 */
class NetworkClient(context: Context) {

    private val appContext = context.applicationContext
    private val socket = DatagramSocket().apply { broadcast = true }
    private val target = AtomicReference<InetSocketAddress?>(null)
    private val lastPacket = AtomicReference<ByteArray?>(null)

    @Volatile private var running = true
    private var multicastLock: WifiManager.MulticastLock? = null

    init {
        // Some devices need a multicast lock to receive the discovery reply.
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("ps2pad")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        Thread({ repeatLoop() }, "ps2pad-repeat").apply { isDaemon = true; start() }
    }

    /** Point the client at a specific PC IP. */
    fun setTarget(ip: String) {
        runCatching {
            target.set(InetSocketAddress(InetAddress.getByName(ip.trim()), PORT))
        }
    }

    fun targetIp(): String? = target.get()?.address?.hostAddress

    /** Queue a packet to be sent immediately. */
    fun send(packet: ByteArray) {
        lastPacket.set(packet)
        val dst = target.get() ?: return
        runCatching {
            socket.send(DatagramPacket(packet, packet.size, dst.address, dst.port))
        }
    }

    private fun repeatLoop() {
        while (running) {
            val dst = target.get()
            val pkt = lastPacket.get()
            if (dst != null && pkt != null) {
                runCatching {
                    socket.send(DatagramPacket(pkt, pkt.size, dst.address, dst.port))
                }
            }
            try { Thread.sleep(33) } catch (_: InterruptedException) { break }
        }
    }

    /**
     * Broadcast a discovery probe and wait up to ~1.2s for the server's reply.
     * Returns the PC's IP on success, or null. Run this off the UI thread.
     */
    fun discover(): String? {
        val probe = DISCOVER_MSG.toByteArray()
        val targets = broadcastAddresses()
        val ds = DatagramSocket().apply {
            broadcast = true
            soTimeout = 1200
        }
        try {
            for (addr in targets) {
                runCatching {
                    ds.send(DatagramPacket(probe, probe.size, addr, PORT))
                }
            }
            val buf = ByteArray(64)
            val reply = DatagramPacket(buf, buf.size)
            val deadline = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < deadline) {
                try {
                    ds.receive(reply)
                } catch (_: Exception) {
                    break
                }
                val text = String(reply.data, 0, reply.length)
                if (text.startsWith(REPLY_MSG)) {
                    return reply.address.hostAddress
                }
            }
        } finally {
            ds.close()
        }
        return null
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        // global broadcast
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        // typical Android-hotspot gateway ranges, as a fallback for devices
        // that drop 255.255.255.255 broadcasts
        for (net in listOf("192.168.43.255", "192.168.42.255", "192.168.137.255")) {
            runCatching { list.add(InetAddress.getByName(net)) }
        }
        return list
    }

    fun close() {
        running = false
        runCatching { socket.close() }
        runCatching { multicastLock?.release() }
    }

    companion object {
        const val PORT = 9999
        const val DISCOVER_MSG = "PS2PAD_DISCOVER"
        const val REPLY_MSG = "PS2PAD_HERE"
    }
}
