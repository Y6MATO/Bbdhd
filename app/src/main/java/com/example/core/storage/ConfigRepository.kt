package com.example.core.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.core.model.AppFilterMode
import com.example.core.model.DpiConfig
import com.example.core.model.DpiPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ConfigRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("byebyedpi_prefs", Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(loadConfig())
    val configFlow: StateFlow<DpiConfig> = _configFlow.asStateFlow()

    private fun loadConfig(): DpiConfig {
        val json = prefs.getString(KEY_CONFIG, null)
        return if (json != null) {
            DpiConfig.fromJson(json)
        } else {
            DpiConfig.defaultForPreset(DpiPreset.YOUTUBE_FIX)
        }
    }

    fun updateConfig(config: DpiConfig) {
        prefs.edit().putString(KEY_CONFIG, config.toJson()).apply()
        _configFlow.value = config
    }

    fun setPreset(preset: DpiPreset) {
        val current = _configFlow.value
        val newConfig = DpiConfig.defaultForPreset(preset).copy(
            appFilterMode = current.appFilterMode,
            selectedPackageNames = current.selectedPackageNames,
            socksPort = current.socksPort,
            enableVpn = current.enableVpn,
            enableSocksServer = current.enableSocksServer
        )
        updateConfig(newConfig)
    }

    fun updateAppFilter(mode: AppFilterMode, packageNames: Set<String>) {
        val updated = _configFlow.value.copy(
            appFilterMode = mode,
            selectedPackageNames = packageNames
        )
        updateConfig(updated)
    }

    companion object {
        private const val KEY_CONFIG = "key_dpi_config"

        @Volatile
        private var INSTANCE: ConfigRepository? = null

        fun getInstance(context: Context): ConfigRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
