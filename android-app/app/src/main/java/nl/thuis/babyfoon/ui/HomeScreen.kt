package nl.thuis.babyfoon.ui

import android.graphics.Bitmap
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.ContextCompat
import nl.thuis.babyfoon.TalkClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nl.thuis.babyfoon.CameraConfig
import nl.thuis.babyfoon.LinkStatus
import nl.thuis.babyfoon.ListenService
import nl.thuis.babyfoon.Monitor
import nl.thuis.babyfoon.MonitorUi
import nl.thuis.babyfoon.Reolink
import nl.thuis.babyfoon.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: Settings,
    settingsVersion: Int,
    onOpenCamera: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by Monitor.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Babyfoon") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Instellingen")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (ui.alarm) AlarmBanner(ui)
            BabyfoonCard(ui, settings)

            Text("Camera's", style = MaterialTheme.typography.titleMedium)
            for (i in 0 until Settings.CAMERA_COUNT) {
                val cfg = remember(settingsVersion) { settings.camera(i) }
                CameraTile(cfg, onClick = { onOpenCamera(i) })
            }
        }
    }
}

@Composable
private fun AlarmBanner(ui: MonitorUi) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Rood)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Verbinding met de babyfoon kwijt!",
                color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            )
            Text(
                if (ui.alarmAcknowledged) "Gezien. Over 5 minuten opnieuw alarm als hij nog weg is."
                else "Ga even kijken in de babykamer.",
                color = Color.Black,
            )
            if (!ui.alarmAcknowledged) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { ListenService.acknowledge(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                ) { Text("Gezien, alarm stil") }
            }
        }
    }
}

@Composable
private fun BabyfoonCard(ui: MonitorUi, settings: Settings) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (kleur, tekst) = when (ui.status) {
                    LinkStatus.UIT -> Color.Gray to "Uit"
                    LinkStatus.VERBINDEN -> Oranje to "Verbinden…"
                    LinkStatus.VERBONDEN -> Groen to "Verbonden"
                    LinkStatus.KWIJT -> Rood to "Verbinding kwijt"
                }
                Box(Modifier.size(14.dp).clip(CircleShape).background(kleur))
                Spacer(Modifier.size(10.dp))
                Text(tekst, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (ui.soundActive) {
                    Text("Geluid!", color = MaterialTheme.colorScheme.secondary, fontSize = 18.sp)
                } else if (ui.status == LinkStatus.VERBONDEN && !ui.gateOpen) {
                    Text("Stil (ruis gedempt)", color = Color.Gray)
                }
            }

            // Volumemeter: -90 dB (stil) tot 0 dB (maximaal)
            val level = ((ui.levelDb + 90f) / 90f).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { if (ui.status == LinkStatus.VERBONDEN) level else 0f },
                modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
                color = if (ui.soundActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
            )

            if (ui.message.isNotBlank() && ui.status != LinkStatus.VERBONDEN) {
                Text(ui.message, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            if (ui.running) {
                TalkButton(ui, settings)
                if (ui.talkError.isNotBlank()) {
                    Text(ui.talkError, style = MaterialTheme.typography.bodySmall, color = Rood)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Geluid laten horen", Modifier.weight(1f))
                    Switch(
                        checked = ui.playAudio,
                        onCheckedChange = { ListenService.setPlayAudio(context, it) },
                    )
                }
                OutlinedButton(onClick = { ListenService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stoppen")
                }
            } else {
                Button(
                    onClick = { ListenService.start(context) },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("Start babyfoon", fontSize = 20.sp) }
            }
        }
    }
}

/** Ingedrukt houden = praten via de speaker in de babykamer. */
@Composable
private fun TalkButton(ui: MonitorUi, settings: Settings) {
    val context = LocalContext.current
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Box(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (ui.talking) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            askMic.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            TalkClient.start(settings.babyfoonHost, settings.babyfoonPort)
                            tryAwaitRelease()
                            TalkClient.stop()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (ui.talking) "Je praat nu… loslaten om te stoppen" else "Ingedrukt houden om te praten",
            fontSize = 18.sp,
            color = if (ui.talking) Color.Black else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CameraTile(cfg: CameraConfig, onClick: () -> Unit) {
    var bitmap by remember(cfg) { mutableStateOf<Bitmap?>(null) }
    var error by remember(cfg) { mutableStateOf<String?>(null) }

    // Elke 3 seconden een nieuw stilstaand beeld, alleen zolang dit scherm open is
    LaunchedEffect(cfg) {
        if (!cfg.configured) return@LaunchedEffect
        while (true) {
            val r = Reolink.snapshot(cfg)
            if (r.value != null) {
                bitmap = r.value
                error = null
            } else {
                error = r.error
            }
            delay(if (r.value != null) 3000 else 10000)
        }
    }

    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = cfg.configured, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b != null) {
                Image(
                    b.asImageBitmap(), contentDescription = cfg.name,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    when {
                        !cfg.configured -> "Nog niet ingesteld"
                        error != null -> error ?: ""
                        else -> "Laden…"
                    },
                    color = Color.Gray,
                )
            }
            Text(
                cfg.name,
                Modifier
                    .align(Alignment.BottomStart)
                    .background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White,
            )
        }
    }
}
