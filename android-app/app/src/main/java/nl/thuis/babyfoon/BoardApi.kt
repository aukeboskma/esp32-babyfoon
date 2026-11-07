package nl.thuis.babyfoon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Instellingen die het bordje zelf doorgeeft via /status. */
data class BoardStatus(
    val version: String,
    val soundThreshold: Float?,
    val gateThreshold: Float?,
    val gateEnabled: Boolean?,
    val canTune: Boolean,
)

/** Kleine HTTP-koppeling met het babyfoon-bordje (naast de audiostream). */
object BoardApi {

    private fun get(cfg: BabyfoonConfig, path: String, timeoutMs: Int = 3000): Pair<Int, String> {
        val conn = URL("http://${cfg.host}:${cfg.port}$path").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        if (cfg.accessKey.isNotBlank()) conn.setRequestProperty(KEY_HEADER, cfg.accessKey.trim())
        try {
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().decodeToString() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** Test voor het instellingenscherm. */
    suspend fun test(cfg: BabyfoonConfig): String = withContext(Dispatchers.IO) {
        if (cfg.host.isBlank()) return@withContext "Vul eerst het IP-adres in"
        try {
            val (code, text) = get(cfg, "/status")
            if (code != 200) return@withContext boardHttpError(code)
            val v = try { JSONObject(text).optString("version") } catch (e: Exception) { "" }
            if (v.isNotBlank()) "Werkt! (firmware $v)" else "Werkt!"
        } catch (e: Exception) {
            "Niet bereikbaar"
        }
    }

    suspend fun status(cfg: BabyfoonConfig): BoardStatus? = withContext(Dispatchers.IO) {
        try {
            val (code, text) = get(cfg, "/status")
            if (code != 200) return@withContext null
            val o = JSONObject(text)
            fun optFloat(key: String): Float? =
                if (o.has(key) && !o.isNull(key)) o.getDouble(key).toFloat() else null
            BoardStatus(
                version = o.optString("version"),
                soundThreshold = optFloat("sound_threshold"),
                gateThreshold = optFloat("gate_threshold"),
                gateEnabled = if (o.has("gate_enabled")) o.optBoolean("gate_enabled") else null,
                canTune = o.optBoolean("tune", false),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Haalt de drempels op en zet ze in de gedeelde toestand. */
    suspend fun refresh(cfg: BabyfoonConfig) {
        val s = status(cfg) ?: return
        Monitor.updateBaby(cfg.id) {
            it.copy(
                boardSoundThreshold = s.soundThreshold,
                boardGateThreshold = if (s.gateEnabled == false) null else s.gateThreshold,
                canTune = s.canTune,
                boardVersion = s.version,
            )
        }
    }

    /**
     * Laat het bordje 10 seconden de ruis meten en de drempels zelf instellen.
     * Geeft een tekst terug voor op het scherm.
     */
    suspend fun tune(cfg: BabyfoonConfig): String {
        Monitor.updateBaby(cfg.id) { it.copy(tuneMessage = "Ruis meten… 10 seconden stil zijn") }
        val code = withContext(Dispatchers.IO) {
            try {
                get(cfg, "/tune").first
            } catch (e: Exception) {
                -1
            }
        }
        if (code != 200) {
            val msg = when (code) {
                -1 -> "Inmeten lukt niet: babyfoon niet bereikbaar"
                404 -> "Inmeten lukt niet (firmware 2.1 of nieuwer nodig)"
                else -> "Inmeten lukt niet: " + boardHttpError(code)
            }
            Monitor.updateBaby(cfg.id) { it.copy(tuneMessage = msg) }
            return msg
        }
        delay(12_000)
        refresh(cfg)
        val st = Monitor.state.value.babies.firstOrNull { it.id == cfg.id }
        val sound: Float? = st?.boardSoundThreshold
        val gate: Float? = st?.boardGateThreshold
        val msg = if (sound != null) {
            "Klaar: drempel ${sound.toInt()} dB" + (if (gate != null) ", ruisgrens ${gate.toInt()} dB" else "")
        } else {
            "Klaar"
        }
        Monitor.updateBaby(cfg.id) { it.copy(tuneMessage = msg) }
        return msg
    }
}
