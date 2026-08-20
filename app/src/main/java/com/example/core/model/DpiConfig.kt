package com.example.core.model

import org.json.JSONArray
import org.json.JSONObject

enum class DpiPreset(
    val id: String,
    val displayName: String,
    val description: String,
    val iconName: String
) {
    YOUTUBE_FIX(
        id = "youtube_fix",
        displayName = "YouTube Fix & Streaming",
        description = "Optimized for YouTube 4K/1080p unthrottling: TLS record header fragmentation (Pos 1) with Cloudflare DoH.",
        iconName = "youtube"
    ),
    DISCORD_FIX(
        id = "discord_fix",
        displayName = "Discord & Voice Fix",
        description = "SNI domain desync + segment delay for Discord voice gateways and media servers.",
        iconName = "discord"
    ),
    STANDARD(
        id = "standard",
        displayName = "Standard Circumvention",
        description = "Balanced TCP fragmentation at offset 2 + HTTP header casing for general blocked websites.",
        iconName = "shield"
    ),
    AGGRESSIVE(
        id = "aggressive",
        displayName = "Aggressive Desync",
        description = "Fake SNI injection + reverse segment disorder + 10ms delay for strict DPI middleboxes.",
        iconName = "bolt"
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom Parameters",
        description = "Fully user-configured fragmentation offsets, HTTP tricks, fake payloads, and DNS resolvers.",
        iconName = "tune"
    )
}

enum class SplitPosition(val code: Int, val label: String, val description: String) {
    RECORD_START(1, "Split at Byte 1 (TLS Record)", "Splits packet after first byte of TLS header"),
    RECORD_HEADER(2, "Split at Byte 2 (TLS Header)", "Splits after 2 bytes, breaking SSL record recognition"),
    SNI_START(3, "Split at SNI Start", "Splits exactly at the beginning of the Server Name Indication"),
    SNI_MIDDLE(4, "Split at SNI Middle", "Splits the SNI domain string in half (e.g., 'you' + 'tube.com')"),
    CUSTOM_OFFSET(5, "Custom Byte Offset", "Uses the exact numeric byte offset specified below")
}

enum class HttpModMode(val id: String, val label: String) {
    NONE("none", "Disabled"),
    LOWERCASE_HOST("lowercase", "Lowercase 'host:' header"),
    UPPERCASE_HOST("uppercase", "Uppercase 'HOST:' header"),
    SPACE_AFTER_HOST("space", "Extra space ('Host:  ')"),
    TAB_AFTER_HOST("tab", "Tab after 'Host:\t'")
}

enum class FakePacketMode(val id: String, val label: String, val description: String) {
    NONE("none", "Disabled", "No fake packet injected"),
    FAKE_GOOGLE("fake_google", "Fake Google SNI", "Sends decoy www.google.com ClientHello first"),
    FAKE_CORRUPT("fake_corrupt", "Fake Corrupt Header", "Sends 0x16 0x03 0x01 with invalid length")
}

enum class DnsProvider(
    val id: String,
    val displayName: String,
    val dohUrl: String,
    val ipFallback: String
) {
    CLOUDFLARE("cloudflare", "Cloudflare DoH (1.1.1.1)", "https://1.1.1.1/dns-query", "1.1.1.1"),
    GOOGLE("google", "Google DoH (8.8.8.8)", "https://dns.google/dns-query", "8.8.8.8"),
    ADGUARD("adguard", "AdGuard DoH (No Ads)", "https://dns.adguard-dns.com/dns-query", "94.140.14.14"),
    QUAD9("quad9", "Quad9 DoH (9.9.9.9)", "https://dns.quad9.net/dns-query", "9.9.9.9"),
    SYSTEM("system", "System Default DNS", "", "8.8.8.8")
}

enum class AppFilterMode(val id: String, val label: String) {
    ALL_APPS("all", "Route All Applications"),
    INCLUDE_SELECTED("include", "Only Route Selected Apps (Whitelist)"),
    EXCLUDE_SELECTED("exclude", "Bypass Selected Apps (Blacklist)")
}

data class DpiConfig(
    val preset: DpiPreset = DpiPreset.YOUTUBE_FIX,
    val splitPosition: SplitPosition = SplitPosition.RECORD_START,
    val customSplitOffset: Int = 1,
    val segmentDelayMs: Long = 5L,
    val fakePacketMode: FakePacketMode = FakePacketMode.NONE,
    val httpModMode: HttpModMode = HttpModMode.LOWERCASE_HOST,
    val dnsProvider: DnsProvider = DnsProvider.CLOUDFLARE,
    val customDohUrl: String = "",
    val socksPort: Int = 1080,
    val enableVpn: Boolean = true,
    val enableSocksServer: Boolean = true,
    val enableTcpNoDelay: Boolean = true,
    val appFilterMode: AppFilterMode = AppFilterMode.ALL_APPS,
    val selectedPackageNames: Set<String> = emptySet(),
    val autoStartOnBoot: Boolean = false,
    val blockUdpQuic: Boolean = true // QUIC/HTTP3 blocking forces browser to fallback to TCP with DPI evasion!
) {
    companion object {
        fun defaultForPreset(preset: DpiPreset): DpiConfig {
            return when (preset) {
                DpiPreset.YOUTUBE_FIX -> DpiConfig(
                    preset = DpiPreset.YOUTUBE_FIX,
                    splitPosition = SplitPosition.RECORD_START,
                    customSplitOffset = 1,
                    segmentDelayMs = 3L,
                    fakePacketMode = FakePacketMode.NONE,
                    httpModMode = HttpModMode.LOWERCASE_HOST,
                    dnsProvider = DnsProvider.CLOUDFLARE,
                    blockUdpQuic = true
                )
                DpiPreset.DISCORD_FIX -> DpiConfig(
                    preset = DpiPreset.DISCORD_FIX,
                    splitPosition = SplitPosition.SNI_START,
                    customSplitOffset = 2,
                    segmentDelayMs = 5L,
                    fakePacketMode = FakePacketMode.NONE,
                    httpModMode = HttpModMode.NONE,
                    dnsProvider = DnsProvider.GOOGLE,
                    blockUdpQuic = false
                )
                DpiPreset.STANDARD -> DpiConfig(
                    preset = DpiPreset.STANDARD,
                    splitPosition = SplitPosition.RECORD_HEADER,
                    customSplitOffset = 2,
                    segmentDelayMs = 2L,
                    fakePacketMode = FakePacketMode.NONE,
                    httpModMode = HttpModMode.LOWERCASE_HOST,
                    dnsProvider = DnsProvider.GOOGLE,
                    blockUdpQuic = true
                )
                DpiPreset.AGGRESSIVE -> DpiConfig(
                    preset = DpiPreset.AGGRESSIVE,
                    splitPosition = SplitPosition.RECORD_START,
                    customSplitOffset = 1,
                    segmentDelayMs = 12L,
                    fakePacketMode = FakePacketMode.FAKE_GOOGLE,
                    httpModMode = HttpModMode.SPACE_AFTER_HOST,
                    dnsProvider = DnsProvider.CLOUDFLARE,
                    blockUdpQuic = true
                )
                DpiPreset.CUSTOM -> DpiConfig(
                    preset = DpiPreset.CUSTOM,
                    splitPosition = SplitPosition.RECORD_START,
                    customSplitOffset = 1,
                    segmentDelayMs = 5L,
                    fakePacketMode = FakePacketMode.NONE,
                    httpModMode = HttpModMode.LOWERCASE_HOST,
                    dnsProvider = DnsProvider.CLOUDFLARE,
                    blockUdpQuic = true
                )
            }
        }

        fun fromJson(jsonStr: String): DpiConfig {
            return try {
                val obj = JSONObject(jsonStr)
                val presetName = obj.optString("preset", DpiPreset.YOUTUBE_FIX.name)
                val splitPosName = obj.optString("splitPosition", SplitPosition.RECORD_START.name)
                val fakeName = obj.optString("fakePacketMode", FakePacketMode.NONE.name)
                val httpName = obj.optString("httpModMode", HttpModMode.LOWERCASE_HOST.name)
                val dnsName = obj.optString("dnsProvider", DnsProvider.CLOUDFLARE.name)
                val filterName = obj.optString("appFilterMode", AppFilterMode.ALL_APPS.name)

                val appsJson = obj.optJSONArray("selectedPackageNames")
                val appsSet = mutableSetOf<String>()
                if (appsJson != null) {
                    for (i in 0 until appsJson.length()) {
                        appsSet.add(appsJson.getString(i))
                    }
                }

                DpiConfig(
                    preset = runCatching { DpiPreset.valueOf(presetName) }.getOrDefault(DpiPreset.YOUTUBE_FIX),
                    splitPosition = runCatching { SplitPosition.valueOf(splitPosName) }.getOrDefault(SplitPosition.RECORD_START),
                    customSplitOffset = obj.optInt("customSplitOffset", 1),
                    segmentDelayMs = obj.optLong("segmentDelayMs", 5L),
                    fakePacketMode = runCatching { FakePacketMode.valueOf(fakeName) }.getOrDefault(FakePacketMode.NONE),
                    httpModMode = runCatching { HttpModMode.valueOf(httpName) }.getOrDefault(HttpModMode.LOWERCASE_HOST),
                    dnsProvider = runCatching { DnsProvider.valueOf(dnsName) }.getOrDefault(DnsProvider.CLOUDFLARE),
                    customDohUrl = obj.optString("customDohUrl", ""),
                    socksPort = obj.optInt("socksPort", 1080),
                    enableVpn = obj.optBoolean("enableVpn", true),
                    enableSocksServer = obj.optBoolean("enableSocksServer", true),
                    enableTcpNoDelay = obj.optBoolean("enableTcpNoDelay", true),
                    appFilterMode = runCatching { AppFilterMode.valueOf(filterName) }.getOrDefault(AppFilterMode.ALL_APPS),
                    selectedPackageNames = appsSet,
                    autoStartOnBoot = obj.optBoolean("autoStartOnBoot", false),
                    blockUdpQuic = obj.optBoolean("blockUdpQuic", true)
                )
            } catch (e: Exception) {
                defaultForPreset(DpiPreset.YOUTUBE_FIX)
            }
        }
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("preset", preset.name)
        obj.put("splitPosition", splitPosition.name)
        obj.put("customSplitOffset", customSplitOffset)
        obj.put("segmentDelayMs", segmentDelayMs)
        obj.put("fakePacketMode", fakePacketMode.name)
        obj.put("httpModMode", httpModMode.name)
        obj.put("dnsProvider", dnsProvider.name)
        obj.put("customDohUrl", customDohUrl)
        obj.put("socksPort", socksPort)
        obj.put("enableVpn", enableVpn)
        obj.put("enableSocksServer", enableSocksServer)
        obj.put("enableTcpNoDelay", enableTcpNoDelay)
        obj.put("appFilterMode", appFilterMode.name)
        obj.put("autoStartOnBoot", autoStartOnBoot)
        obj.put("blockUdpQuic", blockUdpQuic)

        val arr = JSONArray()
        selectedPackageNames.forEach { arr.put(it) }
        obj.put("selectedPackageNames", arr)

        return obj.toString(2)
    }

    fun toCommandLineArgs(): String {
        val sb = StringBuilder()
        when (splitPosition) {
            SplitPosition.RECORD_START -> sb.append("--split-pos 1 ")
            SplitPosition.RECORD_HEADER -> sb.append("--split-pos 2 ")
            SplitPosition.SNI_START -> sb.append("--split-pos sni_start ")
            SplitPosition.SNI_MIDDLE -> sb.append("--split-pos sni_mid ")
            SplitPosition.CUSTOM_OFFSET -> sb.append("--split-pos $customSplitOffset ")
        }
        if (segmentDelayMs > 0) {
            sb.append("--delay ${segmentDelayMs}ms ")
        }
        if (fakePacketMode != FakePacketMode.NONE) {
            sb.append("--fake ${fakePacketMode.id} ")
        }
        if (httpModMode != HttpModMode.NONE) {
            sb.append("--mod-http ${httpModMode.id} ")
        }
        sb.append("--doh ${dnsProvider.id} ")
        sb.append("--port $socksPort ")
        if (blockUdpQuic) {
            sb.append("--drop-quic ")
        }
        return sb.toString().trim()
    }
}
