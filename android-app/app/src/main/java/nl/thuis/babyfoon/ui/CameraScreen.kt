package nl.thuis.babyfoon.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import nl.thuis.babyfoon.CameraConfig
import nl.thuis.babyfoon.Reolink

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(config: CameraConfig, onBack: () -> Unit) {
    val context = LocalContext.current
    var hd by remember { mutableStateOf(false) }
    var withSound by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(context)
            // Kleine buffer = weinig vertraging
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 2000, 250, 500)
                    .build()
            )
            .build()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Geen live beeld: RTSP staat waarschijnlijk uit in de camera " +
                            "(Reolink-app > Netwerk > Geavanceerd > Poortinstellingen)"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                        "Geen live beeld: inlog geweigerd"
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        "Telefoon kan dit beeld niet afspelen; probeer zonder HD"
                    else -> "Geen live beeld: ${e.errorCodeName}"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(hd) {
        error = null
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // betrouwbaarder over wifi
            .createMediaSource(MediaItem.fromUri(Reolink.rtspUrl(config, hd)))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    LaunchedEffect(withSound) {
        // Standaard stil, zodat je de babyfoon blijft horen
        player.volume = if (withSound) 1f else 0f
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            this.player = player
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                error?.let { Text(it, color = Color.White, modifier = Modifier.padding(16.dp)) }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = hd, onClick = { hd = !hd }, label = { Text("Scherp (HD)") })
                FilterChip(
                    selected = withSound, onClick = { withSound = !withSound },
                    label = { Text("Geluid camera") },
                )
            }

            if (config.ptz) {
                PtzPad(config)
            }
        }
    }
}

/** Ingedrukt houden = draaien, loslaten = stoppen. */
@Composable
private fun PtzPad(config: CameraConfig) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HoldButton(config, "Up") { Icon(Icons.Filled.KeyboardArrowUp, "Omhoog", Modifier.size(40.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HoldButton(config, "Left") {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Links", Modifier.size(40.dp))
            }
            Spacer(Modifier.size(72.dp))
            HoldButton(config, "Right") {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Rechts", Modifier.size(40.dp))
            }
        }
        HoldButton(config, "Down") { Icon(Icons.Filled.KeyboardArrowDown, "Omlaag", Modifier.size(40.dp)) }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HoldButton(config, "ZoomDec") { Text("−", fontSize = 32.sp) }
            HoldButton(config, "ZoomInc") { Text("+", fontSize = 32.sp) }
        }
        Text("Ingedrukt houden om te draaien of te zoomen", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HoldButton(config: CameraConfig, op: String, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(op) {
                detectTapGestures(
                    onPress = {
                        scope.launch { Reolink.ptz(config, op) }
                        tryAwaitRelease()
                        scope.launch { Reolink.ptz(config, "Stop") }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}
