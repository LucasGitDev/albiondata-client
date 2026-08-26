package com.albiondata.client

import android.net.VpnService
import android.util.Log
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forwards UDP packets from TUN to the real internet via protected DatagramChannels,
 * then writes server responses back to the TUN fd.
 *
 * Each unique (srcIP:srcPort) tuple gets its own DatagramChannel so the game's
 * UDP flow (Photon on port 5056) maintains its session.
 */
class UdpProxy(
    private val vpnService: VpnService,
    private val tunOutputStream: FileOutputStream,
) {
    companion object {
        private const val TAG = "UdpProxy"
        private const val MAX_PACKET = 65535
        private const val CHANNEL_IDLE_MS = 30_000L
    }

    private val running = AtomicBoolean(false)
    private val selector = Selector.open()

    // Key: "srcIP:srcPort" → channel info
    private data class ChannelEntry(
        val channel: DatagramChannel,
        val originalSrcIP: ByteArray,
        val originalSrcPort: Int,
        val originalDstIP: ByteArray,
        val originalDstPort: Int,
        @Volatile var lastUsed: Long = System.currentTimeMillis(),
    )

    private val channels = ConcurrentHashMap<String, ChannelEntry>()
    private var selectorThread: Thread? = null

    fun start() {
        running.set(true)
        selectorThread = Thread({ receiveLoop() }, "udp-proxy-receiver").also { it.start() }
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        selectorThread?.join(2000)
        channels.values.forEach { runCatching { it.channel.close() } }
        channels.clear()
        runCatching { selector.close() }
    }

    fun forward(
        srcIP: ByteArray,
        srcPort: Int,
        dstIP: ByteArray,
        dstPort: Int,
        payload: ByteArray,
    ) {
        val key = "${srcIP.toHex()}:$srcPort"
        val entry = channels.getOrPut(key) {
            val ch = DatagramChannel.open().apply {
                configureBlocking(false)
                vpnService.protect(socket())
            }
            ChannelEntry(ch, srcIP.copyOf(), srcPort, dstIP.copyOf(), dstPort).also {
                synchronized(selector) {
                    selector.wakeup()
                    ch.register(selector, SelectionKey.OP_READ, it)
                }
            }
        }
        entry.lastUsed = System.currentTimeMillis()

        try {
            val dst = InetSocketAddress(IpPacketHelper.inetAddressFrom(dstIP), dstPort)
            val buf = ByteBuffer.wrap(payload)
            entry.channel.send(buf, dst)
        } catch (e: Exception) {
            Log.w(TAG, "UDP forward failed for $key: ${e.message}")
            channels.remove(key)
            runCatching { entry.channel.close() }
        }
    }

    private fun receiveLoop() {
        val buf = ByteBuffer.allocate(MAX_PACKET)
        var lastCleanup = System.currentTimeMillis()

        while (running.get()) {
            try {
                val ready = selector.select(1000)
                if (!running.get()) break

                val now = System.currentTimeMillis()

                if (ready > 0) {
                    val keys = selector.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        keys.remove()
                        if (!key.isReadable) continue

                        val entry = key.attachment() as? ChannelEntry ?: continue
                        buf.clear()

                        try {
                            val sender = entry.channel.receive(buf) ?: continue
                            buf.flip()
                            val responsePayload = ByteArray(buf.remaining())
                            buf.get(responsePayload)

                            // Write response back to TUN with src/dst swapped
                            val responsePacket = IpPacketHelper.buildIpv4UdpPacket(
                                srcIP = entry.originalDstIP,
                                srcPort = entry.originalDstPort,
                                dstIP = entry.originalSrcIP,
                                dstPort = entry.originalSrcPort,
                                payload = responsePayload,
                            )
                            synchronized(tunOutputStream) {
                                tunOutputStream.write(responsePacket)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "UDP receive error: ${e.message}")
                        }
                    }
                }

                // Cleanup idle channels every 10s
                if (now - lastCleanup > 10_000) {
                    lastCleanup = now
                    val idle = channels.entries.filter { now - it.value.lastUsed > CHANNEL_IDLE_MS }
                    idle.forEach { (k, v) ->
                        channels.remove(k)
                        runCatching { v.channel.close() }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Selector error: ${e.message}")
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString(".") { (it.toInt() and 0xFF).toString() }
}
