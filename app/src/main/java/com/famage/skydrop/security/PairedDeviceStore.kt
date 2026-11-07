package com.famage.skydrop.security

import android.content.Context
import android.content.SharedPreferences
import com.famage.skydrop.model.PairedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class PairedDeviceStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _pairedDevices = MutableStateFlow<List<PairedDevice>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDevice>> = _pairedDevices.asStateFlow()

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_PEERS_JSON) {
            loadDevices()
        }
    }

    companion object {
        private const val PREFS_NAME = "skydrop_paired_peers"
        private const val KEY_PEERS_JSON = "peers_json"
    }

    init {
        loadDevices()
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    @Synchronized
    fun saveDevice(device: PairedDevice) {
        loadDevices()
        val current = _pairedDevices.value.filter { it.id != device.id }.toMutableList()
        current.add(device)
        persist(current)
    }

    @Synchronized
    fun updateDeviceEndpoint(id: String, host: String, port: Int) {
        loadDevices()
        val existing = _pairedDevices.value.find { it.id == id } ?: return
        if (existing.host != host || existing.port != port) {
            val updated = existing.copy(host = host, port = port)
            saveDevice(updated)
        }
    }

    @Synchronized
    fun getDevice(id: String): PairedDevice? {
        loadDevices()
        return _pairedDevices.value.find { it.id == id }
    }

    @Synchronized
    fun getDeviceByHost(host: String): PairedDevice? {
        loadDevices()
        return _pairedDevices.value.find { it.host == host }
    }

    @Synchronized
    fun removeDevice(id: String) {
        loadDevices()
        val filtered = _pairedDevices.value.filter { it.id != id }
        persist(filtered)
    }

    private fun loadDevices() {
        val rawJson = prefs.getString(KEY_PEERS_JSON, null) ?: return
        val list = mutableListOf<PairedDevice>()

        try {
            val jsonArray = JSONArray(rawJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    PairedDevice(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        host = obj.getString("host"),
                        port = obj.getInt("port"),
                        isTls = obj.optBoolean("is_tls", true),
                        fingerprint = obj.getString("fingerprint"),
                        authToken = obj.getString("auth_token"),
                        pairedAt = obj.optLong("paired_at", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {
            // Keep empty list on corrupt storage
        }

        _pairedDevices.value = list
    }

    private fun persist(list: List<PairedDevice>) {
        val array = JSONArray()
        for (dev in list) {
            val obj = JSONObject().apply {
                put("id", dev.id)
                put("name", dev.name)
                put("host", dev.host)
                put("port", dev.port)
                put("is_tls", dev.isTls)
                put("fingerprint", dev.fingerprint)
                put("auth_token", dev.authToken)
                put("paired_at", dev.pairedAt)
            }
            array.put(obj)
        }

        prefs.edit().putString(KEY_PEERS_JSON, array.toString()).apply()
        _pairedDevices.value = list
    }
}
