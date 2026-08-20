package com.example.core.engine

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.core.model.DpiConfig
import com.example.core.storage.AppLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TunRouter(
    private val vpnInterface: ParcelFileDescriptor,
    private val vpnService: VpnService,
    private val config: DpiConfig
) {
    companion object {
        private const val TAG = "TunRouter"
        private const val MTU = 1500
    }

    private var runJob: Job? = null
    private val inStream = FileInputStream(vpnInterface.fileDescriptor)
    private val outStream = FileOutputStream(vpnInterface.fileDescriptor)
    private val forwarder = DpiDesyncSocketForwarder(config)

    // Active TCP session connections: Key "srcIp:srcPort->dstIp:dstPort" -> Session
    private val activeSessions = ConcurrentHashMap<String, TcpSession>()

    class TcpSession(
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        var clientSeq: Long = 0,
        var serverSeq: Long = 1000,
        var remoteSocket: Socket? = null,
        var isEstablished: Boolean = false,
        var job: Job? = null
    )

    fun start(scope: CoroutineScope) {
        runJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(MTU)
            AppLogManager.info(TAG, "TUN packet router started (MTU: $MTU)")

            try {
                while (isActive) {
                    val length = inStream.read(buffer)
                    if (length <= 0) break

                    handleIpPacket(buffer, length, scope)
                }
            } catch (e: Exception) {
                if (isActive) {
                    AppLogManager.error(TAG, "TUN router loop stopped: ${e.message}")
                }
            } finally {
                cleanup()
            }
        }
    }

    private fun handleIpPacket(packet: ByteArray, length: Int, scope: CoroutineScope) {
        if (length < 20) return

        val versionAndIhl = packet[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return // IPv4 only for local TUN

        val ihl = (versionAndIhl and 0x0F) * 4
        if (length < ihl) return

        val protocol = packet[9].toInt() and 0xFF

        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

        when (protocol) {
            17 -> { // UDP
                handleUdpPacket(packet, length, ihl, srcIp, dstIp, scope)
            }
            6 -> { // TCP
                handleTcpPacket(packet, length, ihl, srcIp, dstIp, scope)
            }
            else -> {
                // Pass or drop other protocols
            }
        }
    }

    private fun handleUdpPacket(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        srcIp: String,
        dstIp: String,
        scope: CoroutineScope
    ) {
        if (length < ihl + 8) return

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)

        // 1. Drop QUIC (Port 443 UDP) if blockUdpQuic is true to force TCP TLS with DPI circumvention!
        if (dstPort == 443 && config.blockUdpQuic) {
            AppLogManager.debug(TAG, "Blocked UDP QUIC to $dstIp:$dstPort (Forcing TCP/TLS fallback)")
            return
        }

        // 2. Intercept DNS (Port 53)
        if (dstPort == 53 && length >= ihl + 8 + 12) {
            val dnsPayloadOffset = ihl + 8
            val dnsPayloadLen = length - dnsPayloadOffset
            val dnsData = packet.copyOfRange(dnsPayloadOffset, length)

            scope.launch(Dispatchers.IO) {
                resolveAndReplyDns(dnsData, srcIp, srcPort, dstIp, dstPort)
            }
            return
        }

        // Standard UDP Relay (e.g. Discord voice UDP, NTP, etc.)
        scope.launch(Dispatchers.IO) {
            relayUdpPacket(packet, ihl, length, srcIp, srcPort, dstIp, dstPort)
        }
    }

    private suspend fun resolveAndReplyDns(
        dnsQuery: ByteArray,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ) = withContext(Dispatchers.IO) {
        try {
            // Extract domain name from DNS query question
            val domain = extractDomainFromDns(dnsQuery)
            if (domain.isNotBlank()) {
                val ips = DohResolver.resolve(domain, config.dnsProvider, config.customDohUrl)
                if (ips.isNotEmpty()) {
                    val resolvedIp = ips.first().address
                    val dnsResponse = buildDnsResponse(dnsQuery, resolvedIp)
                    val ipPacket = buildUdpIpPacket(
                        srcIp = dstIp,
                        srcPort = dstPort,
                        dstIp = srcIp,
                        dstPort = srcPort,
                        payload = dnsResponse
                    )
                    synchronized(outStream) {
                        outStream.write(ipPacket)
                        outStream.flush()
                    }
                    return@withContext
                }
            }
        } catch (e: Exception) {
            // Fallback
        }

        // Direct UDP DNS fallback to provider IP
        try {
            val udpSocket = DatagramSocket()
            vpnService.protect(udpSocket)
            udpSocket.soTimeout = 2500

            val targetDns = InetAddress.getByName(config.dnsProvider.ipFallback)
            val sendPacket = DatagramPacket(dnsQuery, dnsQuery.size, targetDns, 53)
            udpSocket.send(sendPacket)

            val recvBuf = ByteArray(1024)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            udpSocket.receive(recvPacket)

            val responseData = recvBuf.copyOf(recvPacket.length)
            val ipPacket = buildUdpIpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                payload = responseData
            )
            synchronized(outStream) {
                outStream.write(ipPacket)
                outStream.flush()
            }
            udpSocket.close()
        } catch (e: Exception) {
            // DNS resolution timeout
        }
    }

    private fun extractDomainFromDns(query: ByteArray): String {
        if (query.size < 13) return ""
        var pos = 12
        val sb = StringBuilder()
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) break
            pos++
            if (pos + len > query.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(query, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return sb.toString()
    }

    private fun buildDnsResponse(query: ByteArray, ipAddress: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(bos)

        val txId = ((query[0].toInt() and 0xFF) shl 8) or (query[1].toInt() and 0xFF)
        dos.writeShort(txId)
        dos.writeShort(0x8180) // Flags: Standard response, No error
        dos.writeShort(1) // Questions: 1
        dos.writeShort(1) // Answer RRs: 1
        dos.writeShort(0) // Authority
        dos.writeShort(0) // Additional

        // Copy Question section
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            dos.writeByte(len)
            pos++
            if (len == 0) break
            dos.write(query, pos, len)
            pos += len
        }
        dos.writeShort(1) // QType A
        dos.writeShort(1) // QClass IN

        // Answer section
        dos.writeShort(0xC00C) // Name pointer to question
        dos.writeShort(1) // Type A
        dos.writeShort(1) // Class IN
        dos.writeInt(300) // TTL 300s
        dos.writeShort(ipAddress.size) // RDLength
        dos.write(ipAddress) // RData IP

        return bos.toByteArray()
    }

    private fun relayUdpPacket(
        packet: ByteArray,
        ihl: Int,
        length: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ) {
        val payloadOffset = ihl + 8
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) return

        val payload = packet.copyOfRange(payloadOffset, length)

        try {
            val udpSocket = DatagramSocket()
            vpnService.protect(udpSocket)
            udpSocket.soTimeout = 3000

            val targetAddr = InetAddress.getByName(dstIp)
            val sendPacket = DatagramPacket(payload, payloadLen, targetAddr, dstPort)
            udpSocket.send(sendPacket)

            val recvBuf = ByteArray(MTU)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            udpSocket.receive(recvPacket)

            val responseData = recvBuf.copyOf(recvPacket.length)
            val ipPacket = buildUdpIpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                payload = responseData
            )
            synchronized(outStream) {
                outStream.write(ipPacket)
                outStream.flush()
            }
            udpSocket.close()
        } catch (e: Exception) {
            // UDP relay error
        }
    }

    private fun handleTcpPacket(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        srcIp: String,
        dstIp: String,
        scope: CoroutineScope
    ) {
        if (length < ihl + 20) return

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        val seq = (packet[ihl + 4].toLong() and 0xFF shl 24) or
                (packet[ihl + 5].toLong() and 0xFF shl 16) or
                (packet[ihl + 6].toLong() and 0xFF shl 8) or
                (packet[ihl + 7].toLong() and 0xFF)

        val flags = packet[ihl + 13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val sessionKey = "$srcIp:$srcPort->$dstIp:$dstPort"

        if (isSyn && !isAck) {
            // TCP Handshake Initiation
            val session = TcpSession(
                srcIp = srcIp,
                srcPort = srcPort,
                dstIp = dstIp,
                dstPort = dstPort,
                clientSeq = seq + 1,
                serverSeq = 1000L
            )
            activeSessions[sessionKey] = session

            // Reply with SYN+ACK
            val synAckPacket = buildTcpIpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x12, // SYN + ACK
                payload = ByteArray(0)
            )
            session.serverSeq += 1

            synchronized(outStream) {
                outStream.write(synAckPacket)
                outStream.flush()
            }

            // Launch outbound connection to destination
            session.job = scope.launch(Dispatchers.IO) {
                try {
                    val remoteSocket = Socket()
                    vpnService.protect(remoteSocket)
                    remoteSocket.connect(InetSocketAddress(dstIp, dstPort), 5000)
                    session.remoteSocket = remoteSocket
                    session.isEstablished = true

                    // Read from remote and write back to TUN as TCP packets
                    val remoteIn = remoteSocket.getInputStream()
                    val recvBuf = ByteArray(1400)
                    while (isActive && !remoteSocket.isClosed) {
                        val read = remoteIn.read(recvBuf)
                        if (read <= 0) break

                        val dataPacket = buildTcpIpPacket(
                            srcIp = dstIp,
                            srcPort = dstPort,
                            dstIp = srcIp,
                            dstPort = srcPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x18, // PSH + ACK
                            payload = recvBuf.copyOf(read)
                        )
                        session.serverSeq += read

                        synchronized(outStream) {
                            outStream.write(dataPacket)
                            outStream.flush()
                        }
                    }
                } catch (e: Exception) {
                    // Remote socket closed
                } finally {
                    activeSessions.remove(sessionKey)
                }
            }
            return
        }

        val session = activeSessions[sessionKey] ?: return

        if (isRst || isFin) {
            session.remoteSocket?.close()
            activeSessions.remove(sessionKey)
            return
        }

        val tcpHeaderLen = ((packet[ihl + 12].toInt() and 0xF0) shr 4) * 4
        val tcpPayloadOffset = ihl + tcpHeaderLen
        val tcpPayloadLen = length - tcpPayloadOffset

        if (tcpPayloadLen > 0) {
            session.clientSeq = seq + tcpPayloadLen
            val payload = packet.copyOfRange(tcpPayloadOffset, length)

            // Send ACK back for received data
            val ackPacket = buildTcpIpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x10, // ACK
                payload = ByteArray(0)
            )
            synchronized(outStream) {
                outStream.write(ackPacket)
                outStream.flush()
            }

            // Forward payload to remote socket with DPI bypass!
            scope.launch(Dispatchers.IO) {
                try {
                    val socket = session.remoteSocket
                    if (socket != null && !socket.isClosed) {
                        val out = socket.getOutputStream()
                        // Check for TLS or HTTP and desync
                        val tlsInfo = TlsClientHelloParser.parseClientHello(payload, payload.size)
                        if (tlsInfo != null) {
                            val splitOffset = TlsClientHelloParser.calculateSplitOffset(
                                payload, payload.size, tlsInfo, config.splitPosition, config.customSplitOffset
                            )
                            out.write(payload, 0, splitOffset)
                            out.flush()
                            if (config.segmentDelayMs > 0) {
                                kotlinx.coroutines.delay(config.segmentDelayMs)
                            }
                            if (payload.size > splitOffset) {
                                out.write(payload, splitOffset, payload.size - splitOffset)
                                out.flush()
                            }
                            AppLogManager.evasion(TAG, "TUN: Desynced TLS ClientHello to $dstIp:$dstPort (SNI: ${tlsInfo.sni ?: dstIp})")
                        } else {
                            out.write(payload)
                            out.flush()
                        }
                    }
                } catch (e: Exception) {
                    // Outbound write error
                }
            }
        }
    }

    private fun buildUdpIpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipLen = 20
        val udpLen = 8
        val totalLen = ipLen + udpLen + payload.size

        val buf = ByteBuffer.allocate(totalLen)
        // IP Header
        buf.put(0x45.toByte()) // Version 4, IHL 5
        buf.put(0x00.toByte()) // DSCP / ECN
        buf.putShort(totalLen.toShort())
        buf.putShort(0x1234.toShort()) // ID
        buf.putShort(0x0000.toShort()) // Flags & Frag
        buf.put(64.toByte()) // TTL
        buf.put(17.toByte()) // UDP Protocol
        buf.putShort(0) // Checksum placeholder

        val srcBytes = parseIpBytes(srcIp)
        val dstBytes = parseIpBytes(dstIp)
        buf.put(srcBytes)
        buf.put(dstBytes)

        // IP Checksum calculation
        val ipChecksum = calculateChecksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // UDP Header
        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((udpLen + payload.size).toShort())
        buf.putShort(0) // UDP Checksum optional in IPv4

        buf.put(payload)
        return buf.array()
    }

    private fun buildTcpIpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val ipLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipLen + tcpHeaderLen + payload.size

        val buf = ByteBuffer.allocate(totalLen)
        // IP Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0x5678.toShort())
        buf.putShort(0x4000.toShort()) // Don't fragment
        buf.put(64.toByte()) // TTL
        buf.put(6.toByte()) // TCP Protocol
        buf.putShort(0) // Checksum

        val srcBytes = parseIpBytes(srcIp)
        val dstBytes = parseIpBytes(dstIp)
        buf.put(srcBytes)
        buf.put(dstBytes)

        val ipChecksum = calculateChecksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // TCP Header
        buf.position(20)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte()) // Header length: 5 (20 bytes)
        buf.put(flags.toByte())
        buf.putShort(65535.toShort()) // Window size
        buf.putShort(0) // Checksum placeholder
        buf.putShort(0) // Urgent pointer

        if (payload.isNotEmpty()) {
            buf.put(payload)
        }

        // TCP Checksum calculation (pseudo header + TCP header + payload)
        val tcpChecksum = calculateTcpChecksum(srcBytes, dstBytes, buf.array(), 20, tcpHeaderLen + payload.size)
        buf.putShort(36, tcpChecksum.toShort())

        return buf.array()
    }

    private fun calculateTcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        data: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L
        // Pseudo header: Src IP (4B) + Dst IP (4B) + Zero (1B) + Protocol (1B) + TCP Length (2B)
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 6 // Protocol TCP
        sum += length

        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }

        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun parseIpBytes(ip: String): ByteArray {
        val parts = ip.split('.')
        return byteArrayOf(
            parts[0].toInt().toByte(),
            parts[1].toInt().toByte(),
            parts[2].toInt().toByte(),
            parts[3].toInt().toByte()
        )
    }

    fun stop() {
        runJob?.cancel()
        cleanup()
    }

    private fun cleanup() {
        activeSessions.values.forEach {
            runCatching { it.remoteSocket?.close() }
            runCatching { it.job?.cancel() }
        }
        activeSessions.clear()
        runCatching { inStream.close() }
        runCatching { outStream.close() }
        runCatching { vpnInterface.close() }
    }
}
