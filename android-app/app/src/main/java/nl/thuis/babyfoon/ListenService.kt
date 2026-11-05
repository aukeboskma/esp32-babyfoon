package nl.thuis.babyfoon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Achtergronddienst die de babyfoon-stream ontvangt, afspeelt, het
 * geluidsniveau meet en ALARM geeft als er geen data meer binnenkomt.
 * Draait als voorgrondservice zodat Android hem 's nachts niet stopt.
 */
class ListenService : Service() {

    companion object {
        private const val ACTION_START = "nl.thuis.babyfoon.START"
        private const val ACTION_STOP = "nl.thuis.babyfoon.STOP"
        private const val ACTION_ACK = "nl.thuis.babyfoon.ACK"

        const val CH_STATUS = "status"
        const val CH_ALARM = "alarm"
        const val CH_SOUND = "geluid"

        private const val ID_STATUS = 1
        private const val ID_ALARM = 2
        private const val ID_SOUND = 3

        /** Na "gezien" opnieuw alarm als de verbinding na deze tijd nog weg is. */
        private const val ACK_REARM_MS = 5 * 60 * 1000L
        private const val SOUND_MIN_MS = 1000L
        private const val SOUND_NOTIFY_COOLDOWN_MS = 60 * 1000L

        /** Ruisonderdrukking: zo lang blijft het geluid open na het laatste geluidje. */
        private const val GATE_HOLD_MS = 2500L
        private const val GATE_ATTACK_S = 0.01f   // snel open
        private const val GATE_RELEASE_S = 0.4f   // rustig dicht

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, ListenService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP))
        }

        fun acknowledge(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_ACK))
        }

        fun setPlayAudio(ctx: Context, on: Boolean) {
            Settings(ctx).playAudio = on
            Monitor.update { it.copy(playAudio = on) }
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, "Babyfoon actief", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Vaste melding zolang de babyfoon luistert" }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_SOUND, "Geluid in de babykamer", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Melding als er geluid is" }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALARM, "Verbinding kwijt (alarm)", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "Alarm als de babyfoon geen geluid meer doorstuurt"
                        // Het alarmgeluid speelt de app zelf af via het alarmvolume
                        setSound(null, null)
                        enableVibration(true)
                        setBypassDnd(true)
                    }
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var lastDataAt = 0L
    @Volatile private var ackUntil = 0L

    // Instellingen in het geheugen (elke seconde ververst door de waakhond)
    @Volatile private var thresholdDb = -35
    @Volatile private var notifyOnSound = true
    @Volatile private var alarmAfterMs = 10_000L
    @Volatile private var noiseGate = true
    @Volatile private var gateDb = -40
    @Volatile private var playbackGain = 4

    private var gateGain = 0f
    private var gateOpenUntil = 0L

    private var soundSince = 0L
    private var lastSoundNotify = 0L

    private var alarmPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var settings: Settings

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_ACK -> {
                acknowledgeAlarm()
                return START_STICKY
            }
            else -> begin() // START, of herstart door Android (intent == null)
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------
    private fun begin() {
        ServiceCompat.startForeground(
            this, ID_STATUS, statusNotification("Verbinden…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        if (streamJob?.isActive == true) return

        refreshSettings()
        acquireLocks()
        // Geef bij het starten wat extra tijd voordat het alarm afgaat
        lastDataAt = SystemClock.elapsedRealtime() + 5000
        ackUntil = 0L
        Monitor.update {
            MonitorUi(running = true, status = LinkStatus.VERBINDEN, playAudio = settings.playAudio)
        }
        streamJob = scope.launch { streamLoop() }
        watchdogJob = scope.launch { watchdog() }
    }

    private fun shutdown() {
        TalkClient.stop()
        streamJob?.cancel()
        watchdogJob?.cancel()
        connection?.disconnect()
        stopAlarmSound()
        releaseLocks()
        getSystemService(NotificationManager::class.java).cancel(ID_ALARM)
        Monitor.update { MonitorUi(running = false, playAudio = settings.playAudio) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connection?.disconnect()
        stopAlarmSound()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    //  Stream ontvangen en afspelen
    // ------------------------------------------------------------------
    private suspend fun streamLoop() {
        while (currentCoroutineContext().isActive) {
            val host = settings.babyfoonHost
            if (host.isBlank()) {
                Monitor.update { it.copy(message = "Stel eerst het IP-adres van de babyfoon in") }
                delay(3000)
                continue
            }
            var track: AudioTrack? = null
            try {
                val conn = URL("http://$host:${settings.babyfoonPort}/audio.wav")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.useCaches = false
                connection = conn
                if (conn.responseCode != 200) {
                    throw IllegalStateException("Babyfoon antwoordt met ${conn.responseCode}")
                }
                val input = BufferedInputStream(conn.inputStream, 8192)

                val header = ByteArray(44)
                readFully(input, header)
                if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") {
                    throw IllegalStateException("Geen geldige audiostream")
                }
                val rate = le32(header, 24)
                if (rate !in 8000..48000) throw IllegalStateException("Vreemde sample rate $rate")

                track = buildTrack(rate)
                track.play()

                val buf = ByteArray(rate / 10 * 2) // 100 ms
                val out = ByteArray(buf.size)
                var first = true
                while (currentCoroutineContext().isActive) {
                    readFully(input, buf)
                    lastDataAt = SystemClock.elapsedRealtime()
                    if (first) {
                        first = false
                        onConnected()
                    }
                    val db = rmsDb(buf)
                    handleLevel(db)
                    applyGateAndGain(buf, out, db, rate)
                    val st = Monitor.state.value
                    // Tijdens terugpraten niet afspelen, anders zingt het rond
                    track.setVolume(if (st.playAudio && !st.talking) 1f else 0f)
                    // Niet blokkerend: liever een stukje overslaan dan vertraging opbouwen
                    track.write(out, 0, out.size, AudioTrack.WRITE_NON_BLOCKING)
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                onDisconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                track?.release()
                connection?.disconnect()
                connection = null
            }
            delay(1500) // even wachten en opnieuw proberen
        }
    }

    /**
     * Ruisonderdrukking + versterking. Boven de ruisgrens gaat het geluid
     * direct open en blijft het nog even open; daaronder zacht uitfaden.
     * Alleen het AFSPELEN wordt stil: meten, meldingen en alarm lopen door.
     */
    private fun applyGateAndGain(input: ByteArray, out: ByteArray, db: Float, rate: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!noiseGate || db > gateDb) gateOpenUntil = now + GATE_HOLD_MS
        val target = if (now < gateOpenUntil) 1f else 0f
        val up = 1f / (rate * GATE_ATTACK_S)
        val down = 1f / (rate * GATE_RELEASE_S)
        val gain = playbackGain.toFloat()

        val n = input.size / 2
        for (i in 0 until n) {
            gateGain = if (gateGain < target) minOf(target, gateGain + up)
            else maxOf(target, gateGain - down)
            val s = ((input[2 * i].toInt() and 0xFF) or (input[2 * i + 1].toInt() shl 8)).toShort().toInt()
            val v = (s * gateGain * gain).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val open = target > 0f
        if (open != Monitor.state.value.gateOpen) Monitor.update { it.copy(gateOpen = open) }
    }

    private fun buildTrack(rate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf * 2, rate / 2)) // ~250 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun onConnected() {
        stopAlarmSound()
        ackUntil = 0L
        getSystemService(NotificationManager::class.java).cancel(ID_ALARM)
        Monitor.update {
            it.copy(status = LinkStatus.VERBONDEN, alarm = false, alarmAcknowledged = false, message = "")
        }
        updateStatusNotification("Luistert naar de babykamer")
    }

    private fun onDisconnected(reason: String) {
        Monitor.update {
            val s = if (it.status == LinkStatus.VERBONDEN) LinkStatus.KWIJT else it.status
            it.copy(status = if (it.alarm) LinkStatus.KWIJT else s, message = reason, levelDb = -90f)
        }
        updateStatusNotification("Verbinding kwijt, opnieuw proberen…")
    }

    private fun handleLevel(db: Float) {
        val now = SystemClock.elapsedRealtime()
        val loud = db > thresholdDb
        if (loud) {
            if (soundSince == 0L) soundSince = now
        } else {
            soundSince = 0L
        }
        val active = soundSince != 0L && now - soundSince >= SOUND_MIN_MS
        Monitor.update { it.copy(levelDb = db, soundActive = active) }

        if (active && notifyOnSound && now - lastSoundNotify > SOUND_NOTIFY_COOLDOWN_MS) {
            lastSoundNotify = now
            notify(
                ID_SOUND,
                NotificationCompat.Builder(this, CH_SOUND)
                    .setSmallIcon(R.drawable.ic_stat_baby)
                    .setContentTitle("Geluid in de babykamer")
                    .setContentText("Niveau ${db.toInt()} dB")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(openAppIntent())
                    .setAutoCancel(true)
                    .setTimeoutAfter(5 * 60 * 1000L)
                    .build()
            )
        }
    }

    // ------------------------------------------------------------------
    //  Waakhond: geen data = alarm
    // ------------------------------------------------------------------
    private suspend fun watchdog() {
        while (currentCoroutineContext().isActive) {
            delay(1000)
            refreshSettings()
            val now = SystemClock.elapsedRealtime()
            val silentMs = now - lastDataAt
            val limit = alarmAfterMs
            val state = Monitor.state.value

            if (silentMs > limit) {
                if (state.status == LinkStatus.VERBONDEN) {
                    Monitor.update { it.copy(status = LinkStatus.KWIJT) }
                }
                val acknowledged = now < ackUntil
                if (!state.alarm || (state.alarmAcknowledged && !acknowledged)) {
                    raiseAlarm(silentMs / 1000)
                }
            }
        }
    }

    private fun refreshSettings() {
        thresholdDb = settings.thresholdDb
        notifyOnSound = settings.notifyOnSound
        alarmAfterMs = settings.alarmAfterSec * 1000L
        noiseGate = settings.noiseGate
        gateDb = settings.gateDb
        playbackGain = settings.playbackGain.coerceIn(1, 16)
    }

    private fun raiseAlarm(seconds: Long) {
        Monitor.update {
            it.copy(alarm = true, alarmAcknowledged = false, status = LinkStatus.KWIJT)
        }
        startAlarmSound()
        val ack = PendingIntent.getService(
            this, 10,
            Intent(this, ListenService::class.java).setAction(ACTION_ACK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notify(
            ID_ALARM,
            NotificationCompat.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat_baby)
                .setContentTitle("Babyfoon: verbinding kwijt!")
                .setContentText("Al $seconds seconden geen geluid ontvangen. Ga even kijken.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(openAppIntent())
                .addAction(0, "Gezien", ack)
                .build()
        )
    }

    private fun acknowledgeAlarm() {
        stopAlarmSound()
        ackUntil = SystemClock.elapsedRealtime() + ACK_REARM_MS
        Monitor.update { if (it.alarm) it.copy(alarmAcknowledged = true) else it }
    }

    private fun startAlarmSound() {
        if (alarmPlayer != null) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        alarmPlayer = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ListenService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun stopAlarmSound() {
        alarmPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        alarmPlayer = null
    }

    // ------------------------------------------------------------------
    //  Hulpjes
    // ------------------------------------------------------------------
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babyfoon:luisteren")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "babyfoon:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun statusNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 11,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_baby)
            .setContentTitle("Babyfoon")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stoppen", stop)
            .build()
    }

    private fun updateStatusNotification(text: String) = notify(ID_STATUS, statusNotification(text))

    private fun notify(id: Int, n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // Geen toestemming voor meldingen; het scherm toont de status nog wel
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("Stream gestopt")
            off += n
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** Geluidsniveau in dBFS (0 = maximaal, stilte ongeveer -70 tot -90). */
    private fun rmsDb(buf: ByteArray): Float {
        var sum = 0.0
        val n = buf.size / 2
        for (i in 0 until n) {
            val s = ((buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / max(n, 1))
        return (20 * log10(max(rms, 1.0) / 32768.0)).toFloat()
    }
}
