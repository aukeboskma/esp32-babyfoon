package nl.thuis.babyfoon

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Resultaat van een camera-actie: beeld (of true) bij succes, anders een leesbare fout. */
data class CamResult<T>(val value: T?, val error: String?)

/**
 * Directe koppeling met Reolink-camera's op het lokale netwerk.
 *  - Probeert eerst http, dan https (nieuwere firmware heeft http vaak uit)
 *  - Logt in met een token (cmd=Login); werkt met oude en nieuwe firmware
 *  - Geeft een duidelijke foutmelding terug in plaats van alleen "geen beeld"
 */
object Reolink {

    private class Session(val baseUrl: String, val token: String?, val validUntil: Long)

    private val sessions = ConcurrentHashMap<String, Session>()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** RTSP-adres. Substream = snel en zuinig (H.264), main = scherp. */
    fun rtspUrl(c: CameraConfig, main: Boolean): String {
        val stream = if (main) "main" else "sub"
        return "rtsp://${enc(c.user)}:${enc(c.password)}@${c.host.trim()}:554/h264Preview_01_$stream"
    }

    // ------------------------------------------------------------------
    //  Verbinding
    // ------------------------------------------------------------------

    /** Alleen voor camera's op het eigen netwerk: hun https-certificaat is zelfgemaakt. */
    private val trustAllFactory: SSLSocketFactory by lazy {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }.socketFactory
    }

    private fun isPrivateHost(host: String): Boolean {
        val h = host.trim()
        return h.startsWith("192.168.") || h.startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(h) || h.endsWith(".local")
    }

    private fun open(url: String, timeoutMs: Int = 5000): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs + 2000
        conn.useCaches = false
        if (conn is HttpsURLConnection && isPrivateHost(URL(url).host)) {
            conn.sslSocketFactory = trustAllFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    private fun postJson(url: String, body: String): Pair<Int, String> {
        val conn = open(url)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().decodeToString() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is UnknownHostException -> "IP-adres niet gevonden"
        is ConnectException -> "Camera weigert de verbinding (staat HTTP/HTTPS aan?)"
        is SocketTimeoutException -> "Camera reageert niet (klopt het IP-adres?)"
        is javax.net.ssl.SSLException -> "Beveiligde verbinding mislukt"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Uitleg bij de foutcodes van de Reolink-API. */
    private fun rspText(rsp: Int, detail: String): String = when (rsp) {
        -6, -7 -> "Inlog geweigerd: gebruikersnaam of wachtwoord klopt niet"
        -5 -> "Te veel inlogpogingen; wacht even"
        -26 -> "Deze gebruiker heeft geen rechten hiervoor"
        else -> "Camera meldt fout $rsp${if (detail.isNotBlank()) " ($detail)" else ""}"
    }

    /** Foutcode uit een API-antwoord, of null als het goed ging. */
    private fun apiErrorCode(text: String): Pair<Int, String>? = try {
        val o = JSONArray(text).getJSONObject(0)
        if (o.optInt("code", 0) == 0) null else {
            val err = o.optJSONObject("error") ?: JSONObject()
            err.optInt("rspCode", 0) to err.optString("detail")
        }
    } catch (e: Exception) {
        null
    }

    private fun apiError(text: String): String? = apiErrorCode(text)?.let { rspText(it.first, it.second) }

    /**
     * Zoekt uit of http of https werkt en logt in met een token.
     * Valt terug op user/password in de URL als Login niet bestaat (oude firmware).
     */
    private fun session(c: CameraConfig, fresh: Boolean = false): CamResult<Session> {
        val key = "${c.host}|${c.user}"
        val now = System.currentTimeMillis()
        if (!fresh) sessions[key]?.let { if (it.validUntil > now) return CamResult(it, null) }

        val loginBody = JSONArray().put(
            JSONObject().put("cmd", "Login").put(
                "param", JSONObject().put(
                    "User", JSONObject().put("Version", "0")
                        .put("userName", c.user).put("password", c.password)
                )
            )
        ).toString()

        var lastError = "Geen verbinding"
        for (scheme in listOf("http", "https")) {
            val base = "$scheme://${c.host.trim()}"
            try {
                val (code, text) = postJson("$base/cgi-bin/api.cgi?cmd=Login", loginBody)
                if (code != 200) {
                    lastError = "Camera antwoordt met HTTP $code"
                    continue
                }
                apiErrorCode(text)?.let { (rsp, detail) ->
                    // Echte inlogfout: melden. Andere fout (bijv. oude firmware zonder
                    // Login): doorgaan zonder token, met user/password in de URL.
                    if (rsp in listOf(-5, -6, -7, -26)) return CamResult(null, rspText(rsp, detail))
                }
                val token = try {
                    val v = JSONArray(text).getJSONObject(0).getJSONObject("value").getJSONObject("Token")
                    val lease = v.optLong("leaseTime", 3600)
                    v.getString("name") to (now + (lease - 60).coerceAtLeast(60) * 1000)
                } catch (e: Exception) {
                    null
                }
                val s = Session(base, token?.first, token?.second ?: (now + 10 * 60 * 1000))
                sessions[key] = s
                return CamResult(s, null)
            } catch (e: Exception) {
                lastError = friendly(e)
            }
        }
        return CamResult(null, lastError)
    }

    private fun authQuery(c: CameraConfig, s: Session): String =
        if (s.token != null) "token=${s.token}" else "user=${enc(c.user)}&password=${enc(c.password)}"

    // ------------------------------------------------------------------
    //  Stilstaand beeld
    // ------------------------------------------------------------------

    suspend fun snapshot(c: CameraConfig): CamResult<Bitmap> = withContext(Dispatchers.IO) {
        if (!c.configured) return@withContext CamResult(null, "Nog niet ingesteld")
        var attempt = 0
        while (attempt < 2) {
            val sr = session(c, fresh = attempt > 0)
            val s = sr.value ?: return@withContext CamResult(null, sr.error)
            val base = "${s.baseUrl}/cgi-bin/api.cgi?cmd=Snap&channel=0&rs=${System.currentTimeMillis()}&${authQuery(c, s)}"
            // Eerst verkleind (zuiniger); oudere firmware kent dat niet, dan volledig
            val small = fetchImage("$base&width=640&height=360")
            if (small.value != null) return@withContext small
            val full = fetchImage(base)
            if (full.value != null) return@withContext full
            // Token verlopen? Dan één keer opnieuw inloggen
            if (full.error?.contains("Inlog") == true && attempt == 0) {
                attempt++
                continue
            }
            return@withContext full
        }
        CamResult(null, "Geen beeld")
    }

    private fun fetchImage(url: String): CamResult<Bitmap> = try {
        val conn = open(url)
        try {
            val code = conn.responseCode
            val type = conn.contentType ?: ""
            if (code != 200) {
                CamResult(null, "Camera antwoordt met HTTP $code")
            } else if (!type.startsWith("image")) {
                val text = conn.inputStream.use { it.readBytes().decodeToString() }
                CamResult(null, apiError(text) ?: "Camera stuurt geen foto")
            } else {
                val bytes = conn.inputStream.use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
                )
                if (bmp != null) CamResult(bmp, null) else CamResult(null, "Foto kon niet worden gelezen")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        CamResult(null, friendly(e))
    }

    // ------------------------------------------------------------------
    //  Draaien / kantelen / zoomen (E1 Zoom)
    // ------------------------------------------------------------------

    /** op: "Left", "Right", "Up", "Down", "ZoomInc", "ZoomDec" of "Stop". */
    suspend fun ptz(c: CameraConfig, op: String, speed: Int = 32): CamResult<Boolean> = withContext(Dispatchers.IO) {
        val sr = session(c)
        val s = sr.value ?: return@withContext CamResult(null, sr.error)
        try {
            val body = """[{"cmd":"PtzCtrl","action":0,"param":{"channel":0,"op":"$op","speed":$speed}}]"""
            val (code, text) = postJson("${s.baseUrl}/cgi-bin/api.cgi?cmd=PtzCtrl&${authQuery(c, s)}", body)
            if (code != 200) CamResult(null, "Camera antwoordt met HTTP $code")
            else apiError(text)?.let { CamResult(null, it) } ?: CamResult(true, null)
        } catch (e: Exception) {
            CamResult(null, friendly(e))
        }
    }

    /** Volledige test voor het instellingenscherm: verbinding, inlog en foto. */
    suspend fun test(c: CameraConfig): String {
        sessions.remove("${c.host}|${c.user}")
        val r = snapshot(c)
        if (r.value == null) return r.error ?: "Geen beeld"
        val s = sessions["${c.host}|${c.user}"]
        val via = if (s?.baseUrl?.startsWith("https") == true) "https" else "http"
        return "Werkt! (via $via)"
    }
}
