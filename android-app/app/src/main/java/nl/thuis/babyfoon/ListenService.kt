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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Achtergronddienst die naar ALLE ingestelde babyfoons tegelijk luistert:
 * per babyfoon de stream ontvangen, afspelen, het geluidsniveau meten,
 * en ALARM geven als één van de babyfoons geen data meer stuurt.
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
        private const val ID_SOUND_BASE = 100

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

        /** Eén babyfoon wel of niet laten horen (bewaken gaat altijd door). */
        fun setMuted(ctx: Context, id: String, muted: Boolean) {
            val s = Settings(ctx)
            val set = s.mutedIds.toMutableSet()
            if (muted) set.add(id) else set.remove(id)
            s.mutedIds = set
            Monitor.update { it.copy(mutedIds = set) }
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
                        description = "Alarm als een babyfoon geen geluid meer doorstuurt"
                        // Het alarmgeluid speelt de app zelf af via het alarmvolume
                        setSound(null, null)
                        enableVibration(true)
                        setBypassDnd(true)
                    }
            )
        }
    }

    /** Toestand per babyfoon die alleen de dienst nodig heeft. */
    private class Stream(val cfg: BabyfoonConfig, val index: Int) {
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var lastDataAt = 0L
        var gateGain = 0f
        var gateOpenUntil = 0L
        var soundSince = 0L
        var lastSoundNotify = 0L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()
    private val streams = ConcurrentHashMap<String, Stream>()
    @Volatile private var ackUntil = 0L
    /** Welke babyfoons zaten in het laatste alarm (nieuw verlies = opnieuw alarm). */
    private var alarmedIds: Set<String> = emptySet()

    // Instellingen in het geheugen (elke seconde ververst door de waakhond)
    @Volatile private var thresholdDb = -35
    @Volatile private var notifyOnSound = true
    @Volatile private var alarmAfterMs = 10_000L
    @Volatile private var noiseGate = true
    @Volatile private var gateDb = -40
    @Volatile private var playbackGain = 4

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
        if (jobs.any { it.isActive }) return

        refreshSettings()
        acquireLocks()
        ackUntil = 0L
        alarmedIds = emptySet()

        val configs = settings.babyfoons.filter { it.configured }
        streams.clear()
        // Geef bij het starten wat extra tijd voordat het alarm afgaat
        val start = SystemClock.elapsedRealtime() + 5000
        configs.forEachIndexed { i, cfg ->
            streams[cfg.id] = Stream(cfg, i).apply { lastDataAt = start }
        }

        Monitor.update {
            MonitorUi(
                running = true,
                babies = configs.map { BabyState(id = it.id, name = it.name.ifBlank { it.host }) },
                playAudio = settings.playAudio,
                mutedIds = settings.mutedIds,
            )
        }
        if (configs.isEmpty()) {
            updateStatusNotification("Geen babyfoon ingesteld")
        }
        configs.forEach { cfg -> jobs += scope.launch { streamLoop(streams.getValue(cfg.id)) } }
        // Drempels van elk bordje ophalen (en elke 30 s verversen)
        configs.forEach { cfg ->
            jobs += scope.launch {
                while (currentCoroutineContext().isActive) {
                    BoardApi.refresh(cfg)
                    delay(30_000)
                }
            }
        }
        jobs += scope.launch { watchdog() }
    }

    private fun shutdown() {
        TalkClient.stop()
        jobs.forEach { it.cancel() }
        jobs.clear()
        streams.values.forEach { it.connection?.disconnect() }
        stopAlarmSound()
        releaseLocks()
        getSystemService(NotificationManager::class.java).cancel(ID_ALARM)
        Monitor.update {
            MonitorUi(running = false, playAudio = settings.playAudio, mutedIds = settings.mutedIds)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        streams.values.forEach { it.connection?.disconnect() }
        stopAlarmSound()
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    //  Stream ontvangen en afspelen (één per babyfoon)
    // ------------------------------------------------------------------
    private suspend fun streamLoop(st: Stream) {
        val cfg = st.cfg
        while (currentCoroutineContext().isActive) {
            var track: AudioTrack? = null
            try {
                val conn = URL("http://${cfg.host}:${cfg.port}/audio.wav").openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.useCaches = false
                if (cfg.accessKey.isNotBlank()) conn.setRequestProperty(KEY_HEADER, cfg.accessKey.trim())
                st.connection = conn
                if (conn.responseCode != 200) {
                    throw IllegalStateException(boardHttpError(conn.responseCode))
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
                    st.lastDataAt = SystemClock.elapsedRealtime()
                    if (first) {
                        first = false
                        onConnected(st)
                    }
                    val db = rmsDb(buf)
                    handleLevel(st, db)
                    applyGateAndGain(st, buf, out, db, rate)
                    val ui = Monitor.state.value
                    val audible = ui.playAudio && !ui.talking && cfg.id !in ui.mutedIds
                    track.setVolume(if (audible) 1f else 0f)
                    // Niet blokkerend: liever een stukje overslaan dan vertraging opbouwen
                    track.write(out, 0, out.size, AudioTrack.WRITE_NON_BLOCKING)
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                onDisconnected(st, e.message ?: e.javaClass.simpleName)
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                track?.release()
                st.connection?.disconnect()
                st.connection = null
            }
            delay(1500) // even wachten en opnieuw proberen
        }
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

    /**
     * Ruisonderdrukking + versterking. Boven de ruisgrens gaat het geluid
     * direct open en blijft het nog even open; daaronder zacht uitfaden.
     * Alleen het AFSPELEN wordt stil: meten, meldingen en alarm lopen door.
     */
    private fun applyGateAndGain(st: Stream, input: ByteArray, out: ByteArray, db: Float, rate: Int) {
        val now = SystemClock.elapsedRealtime()
        val board = Monitor.state.value.babies.firstOrNull { it.id == st.cfg.id }
        val gate = board?.boardGateThreshold ?: gateDb.toFloat()
        if (!noiseGate || db > gate) st.gateOpenUntil = now + GATE_HOLD_MS
        val target = if (now < st.gateOpenUntil) 1f else 0f
        val up = 1f / (rate * GATE_ATTACK_S)
        val down = 1f / (rate * GATE_RELEASE_S)
        val gain = playbackGain.toFloat()

        var g = st.gateGain
        val n = input.size / 2
        for (i in 0 until n) {
            g = if (g < target) minOf(target, g + up) else maxOf(target, g - down)
            val s = ((input[2 * i].toInt() and 0xFF) or (input[2 * i + 1].toInt() shl 8)).toShort().toInt()
            val v = (s * g * gain).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        st.gateGain = g
        val open = target > 0f
        val cur = Monitor.state.value.babies.firstOrNull { it.id == st.cfg.id }
        if (cur != null && cur.gateOpen != open) Monitor.updateBaby(st.cfg.id) { it.copy(gateOpen = open) }
    }

    private fun onConnected(st: Stream) {
        Monitor.updateBaby(st.cfg.id) { it.copy(status = LinkStatus.VERBONDEN, message = "") }
        checkAlarmCleared()
        updateStatusSummary()
    }

    private fun onDisconnected(st: Stream, reason: String) {
        Monitor.updateBaby(st.cfg.id) {
            val s = if (it.status == LinkStatus.VERBONDEN || it.status == LinkStatus.KWIJT) LinkStatus.KWIJT
            else it.status
            it.copy(status = s, message = reason, levelDb = -90f, soundActive = false)
        }
        updateStatusSummary()
    }

    private fun handleLevel(st: Stream, db: Float) {
        val now = SystemClock.elapsedRealtime()
        val board = Monitor.state.value.babies.firstOrNull { it.id == st.cfg.id }
        val threshold = board?.boardSoundThreshold ?: thresholdDb.toFloat()
        if (db > threshold) {
            if (st.soundSince == 0L) st.soundSince = now
        } else {
            st.soundSince = 0L
        }
        val active = st.soundSince != 0L && now - st.soundSince >= SOUND_MIN_MS
        Monitor.updateBaby(st.cfg.id) { it.copy(levelDb = db, soundActive = active) }

        if (active && notifyOnSound && now - st.lastSoundNotify > SOUND_NOTIFY_COOLDOWN_MS) {
            st.lastSoundNotify = now
            val name = st.cfg.name.ifBlank { "de babykamer" }
            notify(
                ID_SOUND_BASE + st.index,
                NotificationCompat.Builder(this, CH_SOUND)
                    .setSmallIcon(R.drawable.ic_stat_baby)
                    .setContentTitle("Geluid bij $name")
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
    //  Waakhond: een babyfoon zonder data = alarm
    // ------------------------------------------------------------------
    private suspend fun watchdog() {
        while (currentCoroutineContext().isActive) {
            delay(1000)
            refreshSettings()
            val now = SystemClock.elapsedRealtime()

            val lost = streams.values.filter { now - it.lastDataAt > alarmAfterMs }
            lost.forEach { st ->
                val cur = Monitor.state.value.babies.firstOrNull { it.id == st.cfg.id }
                if (cur?.status == LinkStatus.VERBONDEN) {
                    Monitor.updateBaby(st.cfg.id) { it.copy(status = LinkStatus.KWIJT) }
                }
            }
            if (lost.isEmpty()) continue

            val lostIds = lost.map { it.cfg.id }.toSet()
            val state = Monitor.state.value
            val acknowledged = now < ackUntil
            val newLoss = !alarmedIds.containsAll(lostIds)
            if (!state.alarm || newLoss || (state.alarmAcknowledged && !acknowledged)) {
                alarmedIds = lostIds
                raiseAlarm(lost.map { it.cfg.name.ifBlank { it.cfg.host } }, (now - lost.minOf { it.lastDataAt }) / 1000)
            }
        }
    }

    private fun checkAlarmCleared() {
        val now = SystemClock.elapsedRealtime()
        val stillLost = streams.values.filter { now - it.lastDataAt > alarmAfterMs }
        if (stillLost.isEmpty() && Monitor.state.value.alarm) {
            stopAlarmSound()
            ackUntil = 0L
            alarmedIds = emptySet()
            getSystemService(NotificationManager::class.java).cancel(ID_ALARM)
            Monitor.update { it.copy(alarm = false, alarmAcknowledged = false, alarmNames = emptyList()) }
        }
    }

    private fun raiseAlarm(names: List<String>, seconds: Long) {
        Monitor.update { it.copy(alarm = true, alarmAcknowledged = false, alarmNames = names) }
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
                .setContentTitle("Verbinding kwijt: ${names.joinToString(", ")}")
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
    private fun refreshSettings() {
        thresholdDb = settings.thresholdDb
        notifyOnSound = settings.notifyOnSound
        alarmAfterMs = settings.alarmAfterSec * 1000L
        noiseGate = settings.noiseGate
        gateDb = settings.gateDb
        playbackGain = settings.playbackGain.coerceIn(1, 16)
    }

    private fun updateStatusSummary() {
        val babies = Monitor.state.value.babies
        val ok = babies.count { it.status == LinkStatus.VERBONDEN }
        val text = when {
            babies.isEmpty() -> "Geen babyfoon ingesteld"
            ok == babies.size -> "Luistert naar " + babies.joinToString(" en ") { it.name }
            else -> "$ok van ${babies.size} verbonden"
        }
        updateStatusNotification(text)
    }

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
