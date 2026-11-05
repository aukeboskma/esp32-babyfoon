package nl.thuis.babyfoon

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class CameraConfig(
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

    var babyfoonHost: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v.trim()).apply()

    var babyfoonPort: Int
        get() = prefs.getInt("port", 8080)
        set(v) = prefs.edit().putInt("port", v).apply()

    /** Drempel voor "geluid", in dBFS van de stream (0 = maximaal). */
    var thresholdDb: Int
        get() = prefs.getInt("threshold", -35)  // zelfde schaal als "Geluidsniveau" in HA
        set(v) = prefs.edit().putInt("threshold", v).apply()

    /** Na hoeveel seconden zonder geluidsdata het alarm afgaat. */
    var alarmAfterSec: Int
        get() = prefs.getInt("alarm_after", 10)
        set(v) = prefs.edit().putInt("alarm_after", v).apply()

    var notifyOnSound: Boolean
        get() = prefs.getBoolean("notify_sound", true)
        set(v) = prefs.edit().putBoolean("notify_sound", v).apply()

    var playAudio: Boolean
        get() = prefs.getBoolean("play_audio", true)
        set(v) = prefs.edit().putBoolean("play_audio", v).apply()

    /** Ruisonderdrukking: onder deze grens wordt het afspelen stil. */
    var noiseGate: Boolean
        get() = prefs.getBoolean("noise_gate", true)
        set(v) = prefs.edit().putBoolean("noise_gate", v).apply()

    var gateDb: Int
        get() = prefs.getInt("gate_db", -40)
        set(v) = prefs.edit().putInt("gate_db", v).apply()

    /** Versterking bij het afspelen (1 = origineel, 4 = 4x luider). */
    var playbackGain: Int
        get() = prefs.getInt("play_gain", 4)
        set(v) = prefs.edit().putInt("play_gain", v).apply()

    fun camera(index: Int): CameraConfig = CameraConfig(
        name = prefs.getString("cam${index}_name", null) ?: "Camera ${index + 1}",
        host = prefs.getString("cam${index}_host", "") ?: "",
        user = prefs.getString("cam${index}_user", "") ?: "",
        password = prefs.getString("cam${index}_pass", "") ?: "",
        ptz = prefs.getBoolean("cam${index}_ptz", true),
    )

    fun saveCamera(index: Int, c: CameraConfig) {
        prefs.edit()
            .putString("cam${index}_name", c.name.trim())
            .putString("cam${index}_host", c.host.trim())
            .putString("cam${index}_user", c.user.trim())
            .putString("cam${index}_pass", c.password)
            .putBoolean("cam${index}_ptz", c.ptz)
            .apply()
    }

    companion object {
        const val CAMERA_COUNT = 2

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
            // Valt alleen terug als de sleutelopslag van de telefoon kapot is
            context.getSharedPreferences("instellingen_plain", Context.MODE_PRIVATE)
        }
    }
}
