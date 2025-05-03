package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.AssetUrlItem
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.dto.ServerAffiliationInfo
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import android.util.Log

object MmkvManager {
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUBSCRIPTION_IDS = "SUBSCRIPTION_IDS"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"

    private val mainStorage by lazy { MMKV.mmkvWithID("MAIN_STORAGE", MMKV.MULTI_PROCESS_MODE) }
    private val profileFullStorage by lazy { MMKV.mmkvWithID("PROFILE_FULL_STORAGE", MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID("SUB_STORAGE", MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID("SETTINGS_STORAGE", MMKV.MULTI_PROCESS_MODE) }
    private val assetsStorage by lazy { MMKV.mmkvWithID("ASSETS_STORAGE", MMKV.MULTI_PROCESS_MODE) }
    private val rulesetStorage by lazy { MMKV.mmkvWithID("RULESET_STORAGE", MMKV.MULTI_PROCESS_MODE) }

    fun getSelectServer(): String? {
        val selected = mainStorage.decodeString(KEY_SELECTED_SERVER)
        Log.d(AppConfig.TAG, "Selected server GUID: $selected")
        return selected
    }

    fun setSelectServer(guid: String) {
        mainStorage.encode(KEY_SELECTED_SERVER, guid)
        Log.d(AppConfig.TAG, "Set selected server GUID: $guid")
    }

    fun encodeServerList(serverList: MutableList<String>) {
        mainStorage.encode(KEY_ANG_CONFIGS, JsonUtil.toJson(serverList))
        Log.d(AppConfig.TAG, "Encoded server list with ${serverList.size} servers")
    }

    fun decodeServerList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_ANG_CONFIGS)
        val list = if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJson(json, Array<String>::class.java).toMutableList()
        }
        Log.d(AppConfig.TAG, "Decoded server list with ${list.size} servers")
        return list
    }

    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        profileFullStorage.encode(key, JsonUtil.toJson(config))
        val serverList = decodeServerList()
        if (!serverList.contains(key)) {
            serverList.add(0, key)
            encodeServerList(serverList)
            if (getSelectServer().isNullOrBlank()) {
                mainStorage.encode(KEY_SELECTED_SERVER, key)
            }
        }
        Log.d(AppConfig.TAG, "Encoded server config with GUID: $key, Remarks: ${config.remarks}")
        return key
    }

    fun decodeServerConfig(guid: String): ProfileItem? {
        val json = profileFullStorage.decodeString(guid)
        return if (json.isNullOrBlank()) {
            Log.w(AppConfig.TAG, "No config found for GUID: $guid")
            null
        } else {
            try {
                val config = JsonUtil.fromJson(json, ProfileItem::class.java)
                Log.d(AppConfig.TAG, "Decoded server config for GUID: $guid, Remarks: ${config.remarks}")
                config
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error decoding server config for GUID: $guid, Error: ${e.message}", e)
                null
            }
        }
    }

    fun removeServer(guid: String) {
        profileFullStorage.remove(guid)
        val serverList = decodeServerList()
        serverList.remove(guid)
        encodeServerList(serverList)
        if (guid == getSelectServer()) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }
        rulesetStorage.remove(guid)
        Log.d(AppConfig.TAG, "Removed server with GUID: $guid")
    }

    fun removeAllServer(): Int {
        val serverList = decodeServerList()
        val count = serverList.size
        serverList.forEach { guid ->
            profileFullStorage.remove(guid)
            rulesetStorage.remove(guid)
        }
        serverList.clear()
        encodeServerList(serverList)
        mainStorage.remove(KEY_SELECTED_SERVER)
        Log.d(AppConfig.TAG, "Removed all $count servers")
        return count
    }

    fun removeInvalidServer(guid: String): Int {
        val serverList = decodeServerList()
        var count = 0
        if (guid.isBlank()) {
            serverList.toList().forEach { key ->
                val config = decodeServerConfig(key)
                if (config == null || config.server.isNullOrBlank() || config.serverPort.isNullOrBlank()) {
                    removeServer(key)
                    count++
                }
            }
        } else {
            val config = decodeServerConfig(guid)
            if (config == null || config.server.isNullOrBlank() || config.serverPort.isNullOrBlank()) {
                removeServer(guid)
                count++
            }
        }
        Log.d(AppConfig.TAG, "Removed $count invalid servers")
        return count
    }

    fun encodeSubscription(guid: String, subItem: SubscriptionItem) {
        val key = guid.ifBlank { Utils.getUuid() }
        subStorage.encode(key, JsonUtil.toJson(subItem))
        val subsList = decodeSubsList()
        if (!subsList.contains(key)) {
            subsList.add(key)
            encodeSubsList(subsList)
        }
        Log.d(AppConfig.TAG, "Encoded subscription with ID: $key, URL: ${subItem.url}, Remarks: ${subItem.remarks}")
    }

    fun decodeSubscription(guid: String): SubscriptionItem? {
        val json = subStorage.decodeString(guid)
        return if (json.isNullOrBlank()) {
            Log.w(AppConfig.TAG, "No subscription found for ID: $guid")
            null
        } else {
            try {
                val subItem = JsonUtil.fromJson(json, SubscriptionItem::class.java)
                Log.d(AppConfig.TAG, "Decoded subscription for ID: $guid, URL: ${subItem.url}, Remarks: ${subItem.remarks}")
                subItem
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error decoding subscription for ID: $guid, Error: ${e.message}", e)
                null
            }
        }
    }

    fun decodeSubscriptions(): List<Pair<String, SubscriptionItem>> {
        initSubsList()
        val subscriptions = mutableListOf<Pair<String, SubscriptionItem>>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                try {
                    val subItem = JsonUtil.fromJson(json, SubscriptionItem::class.java)
                    subscriptions.add(Pair(key, subItem))
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Error decoding subscription for ID: $key, Error: ${e.message}", e)
                }
            }
        }
        Log.d(AppConfig.TAG, "Decoded ${subscriptions.size} subscriptions")
        return subscriptions
    }

    fun removeSubscription(guid: String) {
        subStorage.remove(guid)
        val subsList = decodeSubsList()
        subsList.remove(guid)
        encodeSubsList(subsList)
        val serverList = decodeServerList()
        serverList.toList().forEach { key ->
            val config = decodeServerConfig(key)
            if (config?.subscriptionId == guid) {
                removeServer(key)
            }
        }
        Log.d(AppConfig.TAG, "Removed subscription with ID: $guid")
    }

    private fun initSubsList() {
        val json = mainStorage.decodeString(KEY_SUBSCRIPTION_IDS)
        if (json.isNullOrBlank()) {
            encodeSubsList(mutableListOf())
        }
    }

    private fun encodeSubsList(subList: MutableList<String>) {
        mainStorage.encode(KEY_SUBSCRIPTION_IDS, JsonUtil.toJson(subList))
        Log.d(AppConfig.TAG, "Encoded subscription list with ${subList.size} IDs")
    }

    private fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUBSCRIPTION_IDS)
        val list = if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJson(json, Array<String>::class.java).toMutableList()
        }
        Log.d(AppConfig.TAG, "Decoded subscription list with ${list.size} IDs")
        return list
    }

    fun encodeSettings(key: String, value: String) {
        settingsStorage.encode(key, value)
        Log.d(AppConfig.TAG, "Encoded setting: $key = $value")
    }

    fun encodeSettings(key: String, value: Boolean) {
        settingsStorage.encode(key, value)
        Log.d(AppConfig.TAG, "Encoded setting: $key = $value")
    }

    fun decodeSettingsString(key: String, defaultValue: String? = null): String? {
        val value = settingsStorage.decodeString(key, defaultValue)
        Log.d(AppConfig.TAG, "Decoded setting: $key = $value")
        return value
    }

    fun decodeSettingsBool(key: String, defaultValue: Boolean = false): Boolean {
        val value = settingsStorage.decodeBool(key, defaultValue)
        Log.d(AppConfig.TAG, "Decoded setting: $key = $value")
        return value
    }

    fun encodeAssetUrl(assetUrlItem: AssetUrlItem) {
        assetsStorage.encode(assetUrlItem.name, JsonUtil.toJson(assetUrlItem))
        Log.d(AppConfig.TAG, "Encoded asset URL: ${assetUrlItem.name}")
    }

    fun decodeAssetUrl(name: String): AssetUrlItem? {
        val json = assetsStorage.decodeString(name)
        return if (json.isNullOrBlank()) {
            Log.w(AppConfig.TAG, "No asset URL found for name: $name")
            null
        } else {
            try {
                val assetUrl = JsonUtil.fromJson(json, AssetUrlItem::class.java)
                Log.d(AppConfig.TAG, "Decoded asset URL: $name")
                assetUrl
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error decoding asset URL for name: $name, Error: ${e.message}", e)
                null
            }
        }
    }

    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        val json = rulesetStorage.decodeString(guid)
        return if (json.isNullOrBlank()) {
            Log.w(AppConfig.TAG, "No affiliation info found for GUID: $guid")
            null
        } else {
            try {
                val info = JsonUtil.fromJson(json, ServerAffiliationInfo::class.java)
                Log.d(AppConfig.TAG, "Decoded affiliation info for GUID: $guid")
                info
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error decoding affiliation info for GUID: $guid, Error: ${e.message}", e)
                null
            }
        }
    }

    fun encodeServerTestDelayMillis(guid: String, testDelayMillis: Long) {
        var info = decodeServerAffiliationInfo(guid)
        if (info == null) {
            info = ServerAffiliationInfo()
        }
        info.testDelayMillis = testDelayMillis
        rulesetStorage.encode(guid, JsonUtil.toJson(info))
        Log.d(AppConfig.TAG, "Encoded test delay for GUID: $guid, Delay: $testDelayMillis ms")
    }

    fun clearAllTestDelayResults(guidList: List<String>) {
        guidList.forEach { guid ->
            val info = decodeServerAffiliationInfo(guid)
            if (info != null) {
                info.testDelayMillis = 0
                rulesetStorage.encode(guid, JsonUtil.toJson(info))
            }
        }
        Log.d(AppConfig.TAG, "Cleared test delay results for ${guidList.size} servers")
    }

    fun encodeRuleset(guid: String, ruleset: RulesetItem) {
        var info = decodeServerAffiliationInfo(guid)
        if (info == null) {
            info = ServerAffiliationInfo()
        }
        info.ruleset = ruleset
        rulesetStorage.encode(guid, JsonUtil.toJson(info))
        Log.d(AppConfig.TAG, "Encoded ruleset for GUID: $guid")
    }

    fun decodeRuleset(guid: String): RulesetItem? {
        val info = decodeServerAffiliationInfo(guid)
        Log.d(AppConfig.TAG, "Decoded ruleset for GUID: $guid")
        return info?.ruleset
    }
}
