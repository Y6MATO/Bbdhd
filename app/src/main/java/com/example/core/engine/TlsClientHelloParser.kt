package com.example.core.engine

import com.example.core.model.SplitPosition
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

data class TlsInfo(
    val sni: String?,
    val sniOffset: Int,
    val sniLength: Int,
    val clientHelloStart: Int
)

object TlsClientHelloParser {

    /**
     * Parses a raw TCP payload to detect TLS ClientHello and extract the SNI.
     */
    fun parseClientHello(data: ByteArray, length: Int): TlsInfo? {
        if (length < 9) return null

        // 1. TLS Record Layer check
        val contentType = data[0].toInt() and 0xFF
        if (contentType != 0x16) return null // 0x16 = Handshake

        val majorVersion = data[1].toInt() and 0xFF
        val minorVersion = data[2].toInt() and 0xFF
        if (majorVersion != 0x03) return null // SSL 3.0 / TLS 1.0-1.3

        val recordLength = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (recordLength <= 0 || length < 5 + minOf(recordLength, 40)) return null

        // 2. Handshake Protocol check
        val handshakeType = data[5].toInt() and 0xFF
        if (handshakeType != 0x01) return null // 0x01 = ClientHello

        var pos = 5 + 4 // Skip Handshake Header (type + 3 bytes length)
        if (pos + 34 > length) return null

        // Skip client version (2 bytes) + client random (32 bytes)
        pos += 34

        // Session ID
        if (pos >= length) return null
        val sessionIdLen = data[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > length) return null

        // Cipher Suites
        val cipherSuitesLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2 + cipherSuitesLen
        if (pos + 1 > length) return null

        // Compression Methods
        val compressionLen = data[pos].toInt() and 0xFF
        pos += 1 + compressionLen
        if (pos + 2 > length) return null

        // Extensions Length
        val extensionsLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2

        val extensionsEnd = minOf(pos + extensionsLen, length)

        // Iterate Extensions to find Server Name Indication (type = 0x0000)
        while (pos + 4 <= extensionsEnd) {
            val extType = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val extLen = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
            pos += 4

            if (extType == 0x0000) { // server_name extension
                if (pos + 5 <= extensionsEnd) {
                    val serverNameListLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    val nameType = data[pos + 2].toInt() and 0xFF
                    if (nameType == 0x00) { // host_name
                        val nameLen = ((data[pos + 3].toInt() and 0xFF) shl 8) or (data[pos + 4].toInt() and 0xFF)
                        val nameStart = pos + 5
                        if (nameStart + nameLen <= length) {
                            val sni = String(data, nameStart, nameLen, StandardCharsets.US_ASCII)
                            return TlsInfo(
                                sni = sni,
                                sniOffset = nameStart,
                                sniLength = nameLen,
                                clientHelloStart = 5
                            )
                        }
                    }
                }
            }

            pos += extLen
        }

        return TlsInfo(
            sni = null,
            sniOffset = -1,
            sniLength = 0,
            clientHelloStart = 5
        )
    }

    /**
     * Calculates the exact byte offset where the packet should be fragmented.
     */
    fun calculateSplitOffset(
        data: ByteArray,
        length: Int,
        tlsInfo: TlsInfo,
        splitPosition: SplitPosition,
        customOffset: Int = 1
    ): Int {
        if (length <= 1) return 1

        return when (splitPosition) {
            SplitPosition.RECORD_START -> {
                // Byte 1: Split after the first byte of the TLS record layer (0x16)
                1
            }
            SplitPosition.RECORD_HEADER -> {
                // Byte 2: Split after the TLS version high byte
                2.coerceAtMost(length - 1)
            }
            SplitPosition.SNI_START -> {
                // Split right before or at the first character of the domain name
                if (tlsInfo.sniOffset in 1 until length) {
                    tlsInfo.sniOffset
                } else {
                    1
                }
            }
            SplitPosition.SNI_MIDDLE -> {
                // Split in the middle of the SNI domain name (e.g. "you" + "tube.com")
                if (tlsInfo.sniOffset in 1 until length && tlsInfo.sniLength > 1) {
                    val mid = tlsInfo.sniOffset + (tlsInfo.sniLength / 2)
                    mid.coerceIn(1, length - 1)
                } else {
                    1
                }
            }
            SplitPosition.CUSTOM_OFFSET -> {
                customOffset.coerceIn(1, length - 1)
            }
        }
    }

    /**
     * Generates a valid TLS ClientHello decoy packet.
     */
    fun generateDecoyClientHello(fakeSni: String = "www.google.com"): ByteArray {
        val sniBytes = fakeSni.toByteArray(StandardCharsets.US_ASCII)
        val sniLen = sniBytes.size

        // Build extension server_name
        val extSniPayload = ByteArrayOutputStream()
        val extDos = DataOutputStream(extSniPayload)
        extDos.writeShort(3 + sniLen) // server name list length
        extDos.writeByte(0x00) // name type: host_name
        extDos.writeShort(sniLen) // host name length
        extDos.write(sniBytes)
        extDos.flush()
        val extSniBytes = extSniPayload.toByteArray()

        // Build extensions block
        val extBlock = ByteArrayOutputStream()
        val extBlockDos = DataOutputStream(extBlock)
        extBlockDos.writeShort(0x0000) // ext type: server_name
        extBlockDos.writeShort(extSniBytes.size)
        extBlockDos.write(extSniBytes)
        extBlockDos.flush()
        val allExtBytes = extBlock.toByteArray()

        // Build Handshake body
        val handshakeBody = ByteArrayOutputStream()
        val hsDos = DataOutputStream(handshakeBody)
        hsDos.writeByte(0x03) // client version TLS 1.2
        hsDos.writeByte(0x03)
        // 32 bytes random
        for (i in 0 until 32) {
            hsDos.writeByte(i * 7)
        }
        hsDos.writeByte(0x00) // session ID len: 0
        hsDos.writeShort(2) // cipher suites len: 2 bytes
        hsDos.writeShort(0x1301) // TLS_AES_128_GCM_SHA256
        hsDos.writeByte(0x01) // compression methods len: 1
        hsDos.writeByte(0x00) // null compression
        hsDos.writeShort(allExtBytes.size) // extensions length
        hsDos.write(allExtBytes)
        hsDos.flush()
        val bodyBytes = handshakeBody.toByteArray()

        // Build Handshake message (type 0x01 + 3 bytes length + body)
        val handshakeMsg = ByteArrayOutputStream()
        handshakeMsg.write(0x01) // ClientHello
        val bodyLen = bodyBytes.size
        handshakeMsg.write((bodyLen shr 16) and 0xFF)
        handshakeMsg.write((bodyLen shr 8) and 0xFF)
        handshakeMsg.write(bodyLen and 0xFF)
        handshakeMsg.write(bodyBytes)
        val fullHandshake = handshakeMsg.toByteArray()

        // Build TLS Record
        val record = ByteArrayOutputStream()
        val recDos = DataOutputStream(record)
        recDos.writeByte(0x16) // Handshake record
        recDos.writeByte(0x03) // TLS 1.0 record version
        recDos.writeByte(0x01)
        recDos.writeShort(fullHandshake.size)
        recDos.write(fullHandshake)
        recDos.flush()

        return record.toByteArray()
    }
}
