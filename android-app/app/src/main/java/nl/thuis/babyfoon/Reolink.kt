package nl.thuis.babyfoon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Directe koppeling met Reolink-camera's op het lokale netwerk.
 * Vereist dat HTTP en RTSP aan staan in de camera
 * (Reolink-app > Instellingen > Netwerk > Geavanceerd > Poortinstellingen).
 */
object Reolink {

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun api(c: CameraConfig, cmd: String): String =
        "http://${c.host}/cgi-bin/api.cgi?cmd=$cmd&user=${enc(c.user)}&password=${enc(c.password)}"

    /** RTSP-adres. Substream = snel en zuinig (H.264), main = scherp. */
    fun rtspUrl(c: CameraConfig, main: Boolean): String {
        val stream = if (main) "main" else "sub"
        return "rtsp://${enc(c.user)}:${enc(c.password)}@${c.host}:554/h264Preview_01_$stream"
    }

    /** Stilstaand beeld voor het overzicht. Geeft null bij een fout. */
    suspend fun snapshot(c: CameraConfig): Bitmap? = withContext(Dispatchers.IO) {
        val base = api(c, "Snap") + "&channel=0&rs=${System.currentTimeMillis()}"
        // Eerst verkleind vragen (zuiniger); oudere firmware kent dat niet, dan volledig
        fetchImage("$base&width=640&height=360") ?: fetchImage(base)
    }

    private fun fetchImage(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        try {
            val type = conn.contentType ?: ""
            if (conn.responseCode != 200 || !type.startsWith("image")) {
                null // meestal: foute inlog of onbekende optie
            } else {
                val bytes = conn.inputStream.use { it.readBytes() }
                // Grote foto's verkleinen om geheugen te sparen
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Draaien/kantelen/zoomen (E1 Zoom).
     * op: "Left", "Right", "Up", "Down", "ZoomInc", "ZoomDec" of "Stop".
     */
    suspend fun ptz(c: CameraConfig, op: String, speed: Int = 32): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = """[{"cmd":"PtzCtrl","action":0,"param":{"channel":0,"op":"$op","speed":$speed}}]"""
            val conn = URL(api(c, "PtzCtrl")).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val reply = conn.inputStream.use { it.readBytes().decodeToString() }
                reply.contains("\"code\" : 0") || reply.contains("\"code\":0")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
