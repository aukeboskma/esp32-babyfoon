package nl.thuis.babyfoon

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BabyfoonConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 8080,
) {
    val configured: Boolean get() = host.isNotBlank()
}

data class CameraConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val user: String,
    val password: String,
    val ptz: Boolean,
) {
    val configured: Boolean get() = host.isNotBlank()
}

/** Alle instellingen, versleuteld opgeslagen op de telefoon. */
class Settings(context: Context) {

    private val prefs: SharedPreferences = open(context.applicationContext)

    // ------------------------------------------------------------------
    //  Babyfoons en camera's (lijsten, zoveel als je wilt)
    // ------------------------------------------------------------------

    var babyfoons: List<BabyfoonConfig>
        get() {
            val json = prefs.getString("babyfoons_json", null)
            if (json != null) return parseBabyfoons(json)
            // Overstap van versie 1.x: één babyfoon
            val oldHost = prefs.getString("host", "") ?: ""
            return if (oldHost.isNotBlank()) {
                listOf(BabyfoonConfig(name = "Babyfoon", host = oldHost, port = prefs.getInt("port", 8080)))
            } else {
                emptyList()
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name.trim())
                    .put("host", it.host.trim()).put("port", it.port))
            }
            prefs.edit().putString("babyfoons_json", arr.toString()).apply()
        }

    var cameras: List<CameraConfig>
        get() {
            val json = prefs.getString("cameras_json", null)
            if (json != null) return parseCameras(json)
            // Overstap van versie 1.x: twee vaste camera's
            return (0 until 2).mapNotNull { i ->
                val host = prefs.getString("cam${i}_host", "") ?: ""
                if (host.isBlank()) null else CameraConfig(
                    name = prefs.getString("cam${i}_name", null) ?: "Camera ${i + 1}",
                    host = host,
                    user = prefs.getString("cam${i}_user", "") ?: "",
                    password = prefs.getString("cam${i}_pass", "") ?: "",
                    ptz = prefs.getBoolean("cam${i}_ptz", true),
                )
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name.trim())
                    .put("host", it.host.trim()).put("user", it.user.trim())
                    .put("password", it.password).put("ptz", it.ptz))
            }
            prefs.edit().putString("cameras_json", arr.toString()).apply()
        }

    /** Babyfoons die je niet wilt horen (wel bewaken). */
    var mutedIds: Set<String>
        get() = prefs.getStringSet("muted_ids", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("muted_ids", v).apply()

    // ------------------------------------------------------------------
    //  Algemeen (geldt voor alle babyfoons)
    // ------------------------------------------------------------------

    /** Drempel voor "geluid", zelfde schaal als "Geluidsniveau" in HA. */
    var thresholdDb: Int
        get() = prefs.getInt("threshold", -35)
        set(v) = prefs.edit().putInt("threshold", v).apply()

    var alarmAfterSec: Int
        get() = prefs.getInt("alarm_after", 10)
        set(v) = prefs.edit().putInt("alarm_after", v).apply()

    var notifyOnSound: Boolean
        get() = prefs.getBoolean("notify_sound", true)
        set(v) = prefs.edit().putBoolean("notify_sound", v).apply()

    var playAudio: Boolean
        get() = prefs.getBoolean("play_audio", true)
        set(v) = prefs.edit().putBoolean("play_audio", v).apply()

    var noiseGate: Boolean
        get() = prefs.getBoolean("noise_gate", true)
        set(v) = prefs.edit().putBoolean("noise_gate", v).apply()

    var gateDb: Int
        get() = prefs.getInt("gate_db", -40)
        set(v) = prefs.edit().putInt("gate_db", v).apply()

    var playbackGain: Int
        get() = prefs.getInt("play_gain", 4)
        set(v) = prefs.edit().putInt("play_gain", v).apply()

    companion object {
        private fun parseBabyfoons(json: String): List<BabyfoonConfig> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BabyfoonConfig(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    host = o.optString("host"),
                    port = o.optInt("port", 8080),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        private fun parseCameras(json: String): List<CameraConfig> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CameraConfig(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    host = o.optString("host"),
                    user = o.optString("user"),
                    password = o.optString("password"),
                    ptz = o.optBoolean("ptz", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        private fun open(context: Context): SharedPreferences = try {
            val key = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "instellingen",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            context.getSharedPreferences("instellingen_plain", Context.MODE_PRIVATE)
        }
    }
}
