package com.aggapp.core.storage

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * 凭据加密落盘（README §3.2 :core:storage）。
 * Keystore 主密钥 + EncryptedSharedPreferences；值序列化为 JSON（token + extra）。
 */
class CredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "agg_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(cred: SavedCredential) {
        val json = JSONObject().apply {
            put("token", cred.token)
            put("capturedAt", cred.capturedAt)
            val extra = JSONObject()
            cred.extra.forEach { (k, v) -> extra.put(k, v) }
            put("extra", extra)
        }
        prefs.edit().putString(cred.platform, json.toString()).apply()
    }

    fun load(platform: String): SavedCredential? = try {
        val raw = prefs.getString(platform, null) ?: return null
        val json = JSONObject(raw)
        val extraJson = json.optJSONObject("extra") ?: JSONObject()
        val extra = buildMap { for (k in extraJson.keys()) put(k, extraJson.getString(k)) }
        SavedCredential(
            platform = platform,
            token = json.getString("token"),
            extra = extra,
            capturedAt = json.getLong("capturedAt"),
        )
    } catch (e: Exception) {
        null
    }

    fun clear(platform: String) {
        prefs.edit().remove(platform).apply()
    }
}

data class SavedCredential(
    val platform: String,
    val token: String,
    val extra: Map<String, String>,
    val capturedAt: Long,
)

/** Base64 工具（调试展示用，不用于安全） */
fun b64(s: String): String = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
