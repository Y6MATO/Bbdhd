package com.example.core.engine

import android.net.VpnService
import com.example.core.model.DpiConfig
import com.example.core.storage.AppLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class LocalSocks5Server(
    private val config: DpiConfig,
    private val vpnService: VpnService? = null
) {
    companion object {
        private const val TAG = "SOCKS5"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val forwarder = DpiDesyncSocketForwarder(config)

    fun start(scope: CoroutineScope) {
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(config.socksPort, 50, InetAddress.getByName("127.0.0.1"))
                AppLogManager.info(TAG, "SOCKS5 proxy server listening on 127.0.0.1:${config.socksPort}")

                while (isActive && serverSocket?.isClosed == false) {
                    val clientSocket = serverSocket?.accept() ?: break
                    scope.launch(Dispatchers.IO) {
                        handleSocksClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    AppLogManager.error(TAG, "SOCKS5 server stopped: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleSocksClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        var remoteSocket: Socket? = null
        try {
            val input = DataInputStream(clientSocket.getInputStream())
            val output = DataOutputStream(clientSocket.getOutputStream())

            // 1. Handshake: Version 5 + Auth methods
            val version = input.readUnsignedByte()
            if (version != 5) {
                clientSocket.close()
                return@withContext
            }

            val numMethods = input.readUnsignedByte()
            val methods = ByteArray(numMethods)
            input.readFully(methods)

            // Respond with No Authentication Required (0x00)
            output.writeByte(5)
            output.writeByte(0)
            output.flush()

            // 2. Request Details
            val reqVer = input.readUnsignedByte()
            val cmd = input.readUnsignedByte()
            val rsv = input.readUnsignedByte()
            val atyp = input.readUnsignedByte()

            if (reqVer != 5 || cmd != 1) { // Only CONNECT command supported
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                clientSocket.close()
                return@withContext
            }

            var targetHost = ""
            var targetAddress: InetAddress? = null

            when (atyp) {
                0x01 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    input.readFully(ipBytes)
                    targetAddress = InetAddress.getByAddress(ipBytes)
                    targetHost = targetAddress.hostAddress ?: ""
                }
                0x03 -> { // Domain Name
                    val domainLen = input.readUnsignedByte()
                    val domainBytes = ByteArray(domainLen)
                    input.readFully(domainBytes)
                    targetHost = String(domainBytes, StandardCharsets.US_ASCII)

                    // Resolve via DoH
                    val resolved = DohResolver.resolve(targetHost, config.dnsProvider, config.customDohUrl)
                    if (resolved.isNotEmpty()) {
                        targetAddress = resolved.first()
                    } else {
                        // Resolution failure
                        output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        output.flush()
                        clientSocket.close()
                        return@withContext
                    }
                }
                0x04 -> { // IPv6
                    val ipBytes = ByteArray(16)
                    input.readFully(ipBytes)
                    targetAddress = InetAddress.getByAddress(ipBytes)
                    targetHost = targetAddress.hostAddress ?: ""
                }
                else -> {
                    clientSocket.close()
                    return@withContext
                }
            }

            val targetPort = input.readUnsignedShort()

            // 3. Connect Outbound Remote Socket
            remoteSocket = Socket()
            vpnService?.protect(remoteSocket) // Prevent looping inside Android VPN!

            remoteSocket.connect(InetSocketAddress(targetAddress, targetPort), 6000)

            // 4. Send Success Response to SOCKS Client
            val localAddr = remoteSocket.localAddress
            val localPort = remoteSocket.localPort

            output.writeByte(5) // VER
            output.writeByte(0) // REP (Success)
            output.writeByte(0) // RSV
            if (localAddr is Inet4Address) {
                output.writeByte(1)
                output.write(localAddr.address)
            } else if (localAddr is Inet6Address) {
                output.writeByte(4)
                output.write(localAddr.address)
            } else {
                output.writeByte(1)
                output.write(byteArrayOf(127, 0, 0, 1))
            }
            output.writeShort(localPort)
            output.flush()

            AppLogManager.tcp(TAG, "Connected tunnel -> $targetHost:$targetPort (${targetAddress?.hostAddress})")

            // 5. Pipe data with DPI desync
            forwarder.forwardBidirectional(clientSocket, remoteSocket, targetHost)

        } catch (e: Exception) {
            runCatching { clientSocket.close() }
            runCatching { remoteSocket?.close() }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
            serverJob?.cancel()
            AppLogManager.info(TAG, "SOCKS5 proxy server stopped")
        } catch (e: Exception) {
            // Closed
        }
    }
}
