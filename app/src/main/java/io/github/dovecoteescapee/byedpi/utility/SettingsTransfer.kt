package io.github.dovecoteescapee.byedpi.utility

import android.content.Context
import com.amurcanov.tgwsproxy.SettingsStore
import org.json.JSONArray
import org.json.JSONObject

object SettingsTransfer {
    private const val FORMAT = "limeflow_settings"
    private const val VERSION = 1

    suspend fun export(context: Context): String {
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("limeflow", exportSharedPreferences(context))
            .put("proxy", SettingsStore(context.applicationContext).exportSettings())
            .toString(2)
    }

    suspend fun import(context: Context, raw: String) {
        val payload = JSONObject(raw)
        require(payload.optString("format") == FORMAT) { "Unsupported settings file" }
        require(payload.optInt("version") in 1..VERSION) { "Unsupported settings version" }
        importSharedPreferences(context, payload.getJSONObject("limeflow"))
        SettingsStore(context.applicationContext).importSettings(
            payload.optJSONObject("proxy") ?: JSONObject()
        )
    }

    private fun exportSharedPreferences(context: Context): JSONObject {
        val output = JSONObject()
        context.getPreferences().all.forEach { (key, value) ->
            val item = JSONObject()
            when (value) {
                is Boolean -> item.put("type", "boolean").put("value", value)
                is Int -> item.put("type", "int").put("value", value)
                is Long -> item.put("type", "long").put("value", value)
                is Float -> item.put("type", "float").put("value", value.toDouble())
                is String -> item.put("type", "string").put("value", value)
                is Set<*> -> item.put("type", "string_set").put(
                    "value",
                    JSONArray(value.filterIsInstance<String>()),
                )
                else -> return@forEach
            }
            output.put(key, item)
        }
        return output
    }

    private fun importSharedPreferences(context: Context, input: JSONObject) {
        val editor = context.getPreferences().edit().clear()
        input.keys().forEach { key ->
            val item = input.optJSONObject(key) ?: return@forEach
            when (item.optString("type")) {
                "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
                "int" -> editor.putInt(key, item.getInt("value"))
                "long" -> editor.putLong(key, item.getLong("value"))
                "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
                "string" -> editor.putString(key, item.getString("value"))
                "string_set" -> {
                    val values = item.getJSONArray("value")
                    editor.putStringSet(key, buildSet {
                        for (index in 0 until values.length()) {
                            add(values.getString(index))
                        }
                    })
                }
            }
        }
        check(editor.commit()) { "Could not save imported settings" }
    }
}
