package com.example.core.engine

import com.example.core.model.DnsProvider
import com.example.core.storage.AppLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DohResolver {
    private const val TAG = "DoH-Resolver"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // Cache: Host -> Pair(List<InetAddress>, ExpirationTimestamp)
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    suspend fun resolve(
        host: String,
        provider: DnsProvider = DnsProvider.CLOUDFLARE,
        customDohUrl: String = ""
    ): List<InetAddress> = withContext(Dispatchers.IO) {
        // If it's already an IP address, return directly
        if (isIpAddress(host)) {
            return@withContext listOf(InetAddress.getByName(host))
        }

        val now = System.currentTimeMillis()
        val cached = cache[host]
        if (cached != null && cached.second > now && cached.first.isNotEmpty()) {
            return@withContext cached.first
        }

        val dohUrl: String = if (provider == DnsProvider.SYSTEM) {
            try {
                val addresses = InetAddress.getAllByName(host).toList()
                cache[host] = Pair(addresses, now + 300_000L)
                return@withContext addresses
            } catch (e: Exception) {
                listOf(InetAddress.getByName(provider.ipFallback))
            }
            ""
        } else if (customDohUrl.isNotBlank()) {
            customDohUrl
        } else {
            provider.dohUrl
        }

        try {
            // First try JSON DoH endpoint (very fast and universally supported by Cloudflare and Google)
            val jsonUrl = if (dohUrl.contains("cloudflare", ignoreCase = true) || dohUrl.contains("1.1.1.1")) {
                "https://1.1.1.1/dns-query?name=$host&type=A"
            } else if (dohUrl.contains("google", ignoreCase = true)) {
                "https://dns.google/resolve?name=$host&type=A"
            } else {
                "$dohUrl?name=$host&type=A"
            }

            val request = Request.Builder()
                .url(jsonUrl)
                .addHeader("Accept", "application/dns-json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        val json = JSONObject(body)
                        val answerArray = json.optJSONArray("Answer")
                        val addresses = mutableListOf<InetAddress>()
                        if (answerArray != null) {
                            for (i in 0 until answerArray.length()) {
                                val item = answerArray.getJSONObject(i)
                                val type = item.optInt("type")
                                val data = item.optString("data")
                                if ((type == 1 || type == 28) && isIpAddress(data)) {
                                    runCatching {
                                        addresses.add(InetAddress.getByName(data))
                                    }
                                }
                            }
                        }
                        if (addresses.isNotEmpty()) {
                            AppLogManager.dns(TAG, "Resolved '$host' -> ${addresses.first().hostAddress} via DoH ($provider)")
                            cache[host] = Pair(addresses, now + 300_000L) // 5 min cache
                            return@withContext addresses
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogManager.warn(TAG, "DoH resolution failed for '$host': ${e.message}")
        }

        // Fallback to standard DNS
        try {
            val fallback = InetAddress.getAllByName(host).toList()
            cache[host] = Pair(fallback, now + 60_000L)
            return@withContext fallback
        } catch (e: Exception) {
            AppLogManager.error(TAG, "DNS resolution completely failed for '$host': ${e.message}")
            return@withContext emptyList()
        }
    }

    private fun isIpAddress(str: String): Boolean {
        // Quick IPv4 / IPv6 check
        if (str.all { it.isDigit() || it == '.' }) {
            val parts = str.split('.')
            if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) return true
        }
        return str.contains(':')
    }

    fun clearCache() {
        cache.clear()
    }
}
