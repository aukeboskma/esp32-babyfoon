package nl.thuis.babyfoon

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.max

/**
 * Terugpraten: neemt de microfoon van de telefoon op en stuurt het
 * rechtstreeks naar de babyfoon (POST /talk, 16 bit mono 16 kHz).
 * Alleen gebruiken zolang de knop is ingedrukt.
 */
object TalkClient {
    private const val RATE = 16000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var socket: Socket? = null

    /** Vereist dat RECORD_AUDIO al is toegestaan. */
    @SuppressLint("MissingPermission")
    fun start(host: String, port: Int) {
        if (job?.isActive == true || host.isBlank()) return
        Monitor.update { it.copy(talking = true, talkError = "") }
        job = scope.launch {
            var rec: AudioRecord? = null
            var ns: NoiseSuppressor? = null
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, port), 3000)
                s.tcpNoDelay = true
                val out = s.getOutputStream()
                out.write(
                    ("POST /talk HTTP/1.0\r\nHost: $host\r\n" +
                        "Content-Type: audio/L16;rate=$RATE;channels=1\r\n\r\n").toByteArray()
                )

                val minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, RATE / 5 * 2),
                )
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
                }
                rec.startRecording()

                val buf = ByteArray(RATE / 50 * 2) // 20 ms
                while (currentCoroutineContext().isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) out.write(buf, 0, n)
                }
            } catch (e: Exception) {
                if (Monitor.state.value.talking) {
                    Monitor.update { it.copy(talkError = "Terugpraten lukt niet: ${e.message ?: "geen verbinding"}") }
                }
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release()
                ns?.release()
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                Monitor.update { it.copy(talking = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        // Socket sluiten breekt een blokkerende write direct af
        try { socket?.close() } catch (_: Exception) {}
        Monitor.update { it.copy(talking = false) }
    }
}
