package com.ljyh.mei.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persisted ring buffer (last [AaBrowseDiagnostics.CAPACITY]) surviving process death. */
class AaBrowseDiagnosticsPrefs(context: Context) : AaBrowseDiagnostics.Recorder {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun record(entry: AaBrowseDiagnostics.Entry) {
        synchronized(lock) {
            val list = loadLocked().toMutableList()
            list.add(entry)
            while (list.size > AaBrowseDiagnostics.CAPACITY) {
                list.removeAt(0)
            }
            saveLocked(list)
        }
    }

    override fun snapshot(): List<AaBrowseDiagnostics.Entry> = synchronized(lock) {
        loadLocked().asReversed() // newest first for UI
    }

    override fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun loadLocked(): List<AaBrowseDiagnostics.Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        AaBrowseDiagnostics.Entry(
                            timestampMs = o.optLong("ts"),
                            packageName = o.optString("pkg"),
                            uid = o.optInt("uid"),
                            isTrusted = o.optBoolean("trusted"),
                            controllerVersion = o.optInt("ver"),
                            isLegacy = o.optBoolean("legacy"),
                            method = o.optString("method"),
                            parentId = o.optString("parentId").takeIf { it.isNotEmpty() },
                            result = o.optString("result"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLocked(list: List<AaBrowseDiagnostics.Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject()
                    .put("ts", e.timestampMs)
                    .put("pkg", e.packageName)
                    .put("uid", e.uid)
                    .put("trusted", e.isTrusted)
                    .put("ver", e.controllerVersion)
                    .put("legacy", e.isLegacy)
                    .put("method", e.method)
                    .put("parentId", e.parentId ?: "")
                    .put("result", e.result),
            )
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "aa_browse_diagnostics"
        private const val KEY_ENTRIES = "entries"
    }
}
