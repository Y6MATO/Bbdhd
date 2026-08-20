package com.example.core.engine

import com.example.core.model.DpiConfig
import com.example.core.model.FakePacketMode
import com.example.core.storage.AppLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

class DpiDesyncSocketForwarder(
    private val config: DpiConfig,
    private val onBytesTransferred: ((bytesIn: Long, bytesOut: Long) -> Unit)? = null
) {
    companion object {
        private const val TAG = "DpiDesync"
        val totalBytesIn = AtomicLong(0)
        val totalBytesOut = AtomicLong(0)
        val activeConnections = AtomicLong(0)
        val totalConnections = AtomicLong(0)
    }

    /**
     * Forwards data bidirectionally between client and remote sockets,
     * applying DPI circumvention tactics on the outbound stream.
     */
    suspend fun forwardBidirectional(
        clientSocket: Socket,
        remoteSocket: Socket,
        hostHeader: String = ""
    ) = withContext(Dispatchers.IO) {
        activeConnections.incrementAndGet()
        totalConnections.incrementAndGet()

        try {
            if (config.enableTcpNoDelay) {
                remoteSocket.tcpNoDelay = true
                clientSocket.tcpNoDelay = true
            }

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()
            val remoteOut = remoteSocket.getOutputStream()

            coroutineScope {
                val jobUpstream = launch(Dispatchers.IO) {
                    forwardUpstreamWithDpiDesync(clientIn, remoteOut, hostHeader)
                }

                val jobDownstream = launch(Dispatchers.IO) {
                    forwardDownstream(remoteIn, clientOut)
                }

                jobUpstream.join()
                jobDownstream.join()
            }
        } catch (e: Exception) {
            // Socket closed normally or connection reset
        } finally {
            activeConnections.decrementAndGet()
            runCatching { clientSocket.close() }
            runCatching { remoteSocket.close() }
        }
    }

    private suspend fun forwardUpstreamWithDpiDesync(
        clientIn: InputStream,
        remoteOut: OutputStream,
        hostHint: String
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(8192)
        var firstPacket = true

        try {
            while (true) {
                val bytesRead = clientIn.read(buffer)
                if (bytesRead <= 0) break

                if (firstPacket) {
                    firstPacket = false
                    val tlsInfo = TlsClientHelloParser.parseClientHello(buffer, bytesRead)
                    val isHttp = HttpParser.isHttpRequest(buffer, bytesRead)

                    if (tlsInfo != null) {
                        val effectiveSni = tlsInfo.sni ?: hostHint.ifBlank { "unknown" }
                        applyTlsDesync(remoteOut, buffer, bytesRead, tlsInfo, effectiveSni)
                    } else if (isHttp) {
                        val modified = HttpParser.modifyHttpRequest(buffer, bytesRead, config.httpModMode)
                        remoteOut.write(modified)
                        remoteOut.flush()
                        val len = modified.size.toLong()
                        totalBytesOut.addAndGet(len)
                        onBytesTransferred?.invoke(0, len)
                        AppLogManager.evasion(TAG, "Applied HTTP evasion (${config.httpModMode.label}) to $hostHint")
                    } else {
                        remoteOut.write(buffer, 0, bytesRead)
                        remoteOut.flush()
                        totalBytesOut.addAndGet(bytesRead.toLong())
                        onBytesTransferred?.invoke(0, bytesRead.toLong())
                    }
                } else {
                    remoteOut.write(buffer, 0, bytesRead)
                    remoteOut.flush()
                    totalBytesOut.addAndGet(bytesRead.toLong())
                    onBytesTransferred?.invoke(0, bytesRead.toLong())
                }
            }
        } catch (e: Exception) {
            // Stream ended
        }
    }

    private suspend fun applyTlsDesync(
        remoteOut: OutputStream,
        buffer: ByteArray,
        length: Int,
        tlsInfo: TlsInfo,
        sni: String
    ) = withContext(Dispatchers.IO) {
        // 1. Send fake/decoy packet if configured
        when (config.fakePacketMode) {
            FakePacketMode.FAKE_GOOGLE -> {
                val decoy = TlsClientHelloParser.generateDecoyClientHello("www.google.com")
                remoteOut.write(decoy)
                remoteOut.flush()
                AppLogManager.evasion(TAG, "Injected decoy SNI 'www.google.com' for target '$sni'")
                if (config.segmentDelayMs > 0) {
                    delay(config.segmentDelayMs)
                }
            }
            FakePacketMode.FAKE_CORRUPT -> {
                val corrupt = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00)
                remoteOut.write(corrupt)
                remoteOut.flush()
                AppLogManager.evasion(TAG, "Injected corrupt handshake header for target '$sni'")
                if (config.segmentDelayMs > 0) {
                    delay(config.segmentDelayMs)
                }
            }
            FakePacketMode.NONE -> { /* No fake packet */ }
        }

        // 2. Calculate split offset
        val splitOffset = TlsClientHelloParser.calculateSplitOffset(
            buffer,
            length,
            tlsInfo,
            config.splitPosition,
            config.customSplitOffset
        )

        // 3. Write first fragment
        remoteOut.write(buffer, 0, splitOffset)
        remoteOut.flush()

        // 4. Delay segment if configured
        if (config.segmentDelayMs > 0) {
            delay(config.segmentDelayMs)
        }

        // 5. Write remaining fragment
        val remaining = length - splitOffset
        if (remaining > 0) {
            remoteOut.write(buffer, splitOffset, remaining)
            remoteOut.flush()
        }

        val totalLen = length.toLong()
        totalBytesOut.addAndGet(totalLen)
        onBytesTransferred?.invoke(0, totalLen)

        AppLogManager.evasion(
            TAG,
            "Desynced TLS ClientHello for '$sni': Fragment 1 (${splitOffset}B) + Fragment 2 (${remaining}B), delay=${config.segmentDelayMs}ms [${config.splitPosition.label}]"
        )
    }

    private fun forwardDownstream(
        remoteIn: InputStream,
        clientOut: OutputStream
    ) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val bytesRead = remoteIn.read(buffer)
                if (bytesRead <= 0) break
                clientOut.write(buffer, 0, bytesRead)
                clientOut.flush()
                val len = bytesRead.toLong()
                totalBytesIn.addAndGet(len)
                onBytesTransferred?.invoke(len, 0)
            }
        } catch (e: Exception) {
            // Stream ended
        }
    }
}
