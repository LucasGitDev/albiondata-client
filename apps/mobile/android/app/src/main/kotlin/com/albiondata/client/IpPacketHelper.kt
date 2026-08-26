package com.albiondata.client

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

object IpPacketHelper {

    // IP protocol numbers
    const val PROTO_TCP: Int = 6
    const val PROTO_UDP: Int = 17

    // TCP flags
    const val TCP_FLAG_SYN: Int = 0x02
    const val TCP_FLAG_RST: Int = 0x04
    const val TCP_FLAG_ACK: Int = 0x10

    data class Ipv4Header(
        val version: Int,
        val headerLen: Int,
        val protocol: Int,
        val srcIP: ByteArray,
        val dstIP: ByteArray,
        val totalLength: Int,
    )

    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,
        val flags: Int,
        val headerLen: Int,
        val payload: ByteArray,
    )

    fun parseIpv4Header(buf: ByteArray): Ipv4Header? {
        if (buf.size < 20) return null
        val version = (buf[0].toInt() and 0xFF) shr 4
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (buf.size < ihl) return null
        val proto = buf[9].toInt() and 0xFF
        val totalLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val srcIP = buf.copyOfRange(12, 16)
        val dstIP = buf.copyOfRange(16, 20)
        return Ipv4Header(version, ihl, proto, srcIP, dstIP, totalLen)
    }

    fun parseUdpHeader(buf: ByteArray, ipHeaderLen: Int): UdpHeader? {
        val offset = ipHeaderLen
        if (buf.size < offset + 8) return null
        val srcPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        val dstPort = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        val udpLen = ((buf[offset + 4].toInt() and 0xFF) shl 8) or (buf[offset + 5].toInt() and 0xFF)
        val payloadLen = udpLen - 8
        if (payloadLen < 0 || buf.size < offset + 8 + payloadLen) return null
        val payload = buf.copyOfRange(offset + 8, offset + 8 + payloadLen)
        return UdpHeader(srcPort, dstPort, payload)
    }

    fun parseTcpHeader(buf: ByteArray, ipHeaderLen: Int): TcpHeader? {
        val offset = ipHeaderLen
        if (buf.size < offset + 20) return null
        val srcPort = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        val dstPort = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        val seq = (((buf[offset + 4].toLong() and 0xFF) shl 24)
            or ((buf[offset + 5].toLong() and 0xFF) shl 16)
            or ((buf[offset + 6].toLong() and 0xFF) shl 8)
            or (buf[offset + 7].toLong() and 0xFF))
        val dataOffset = ((buf[offset + 12].toInt() and 0xF0) shr 4) * 4
        val flags = buf[offset + 13].toInt() and 0xFF
        val payloadStart = offset + dataOffset
        val payload = if (payloadStart < buf.size) buf.copyOfRange(payloadStart, buf.size) else ByteArray(0)
        return TcpHeader(srcPort, dstPort, seq, flags, dataOffset, payload)
    }

    fun buildIpv4UdpPacket(
        srcIP: ByteArray,
        srcPort: Int,
        dstIP: ByteArray,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        buf.put(0x45.toByte())           // version=4, ihl=5
        buf.put(0x00.toByte())           // DSCP/ECN
        buf.putShort(totalLen.toShort()) // total length
        buf.putShort(0)                  // identification
        buf.putShort(0x4000.toShort())   // flags: don't fragment
        buf.put(64)                      // TTL
        buf.put(PROTO_UDP.toByte())      // protocol
        buf.putShort(0)                  // checksum placeholder
        buf.put(srcIP)
        buf.put(dstIP)

        // IP checksum
        val ipChecksum = checksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // UDP header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // checksum (optional for IPv4, leave 0)
        buf.put(payload)

        return buf.array()
    }

    fun buildTcpRst(
        srcIP: ByteArray,
        srcPort: Int,
        dstIP: ByteArray,
        dstPort: Int,
        ackNum: Long,
    ): ByteArray {
        val tcpLen = 20
        val totalLen = 20 + tcpLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)

        // IP header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64)
        buf.put(PROTO_TCP.toByte())
        buf.putShort(0)
        buf.put(srcIP)
        buf.put(dstIP)

        val ipChecksum = checksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // TCP header
        val tcpStart = 20
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(0)                        // seq=0
        buf.putInt(ackNum.toInt())           // ack
        buf.put((0x50).toByte())             // data offset=5 (20 bytes)
        buf.put((TCP_FLAG_RST or TCP_FLAG_ACK).toByte())
        buf.putShort(0)                      // window
        buf.putShort(0)                      // checksum placeholder
        buf.putShort(0)                      // urgent

        val tcpChecksum = tcpChecksum(buf.array(), srcIP, dstIP, tcpLen)
        buf.putShort(tcpStart + 16, tcpChecksum.toShort())

        return buf.array()
    }

    private fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((length and 1) != 0) {
            sum += (buf[offset + length - 1].toInt() and 0xFF) shl 8
        }
        sum = (sum shr 16) + (sum and 0xFFFF)
        sum += (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(buf: ByteArray, srcIP: ByteArray, dstIP: ByteArray, tcpLen: Int): Int {
        // Pseudo-header: src IP, dst IP, zero, protocol, TCP length
        val pseudo = ByteArray(12 + tcpLen)
        System.arraycopy(srcIP, 0, pseudo, 0, 4)
        System.arraycopy(dstIP, 0, pseudo, 4, 4)
        pseudo[8] = 0
        pseudo[9] = PROTO_TCP.toByte()
        pseudo[10] = (tcpLen shr 8).toByte()
        pseudo[11] = (tcpLen and 0xFF).toByte()
        System.arraycopy(buf, 20, pseudo, 12, tcpLen)
        return checksum(pseudo, 0, pseudo.size)
    }

    fun inetAddressFrom(ip: ByteArray): InetAddress = InetAddress.getByAddress(ip)
}
