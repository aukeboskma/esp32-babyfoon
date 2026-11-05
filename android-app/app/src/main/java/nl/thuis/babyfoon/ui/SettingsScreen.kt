package nl.thuis.babyfoon.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.thuis.babyfoon.CameraConfig
import nl.thuis.babyfoon.Reolink
import nl.thuis.babyfoon.Settings
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(settings.babyfoonHost) }
    var port by remember { mutableStateOf(settings.babyfoonPort.toString()) }
    var threshold by remember { mutableStateOf(settings.thresholdDb.toString()) }
    var alarmAfter by remember { mutableStateOf(settings.alarmAfterSec.toString()) }
    var notifySound by remember { mutableStateOf(settings.notifyOnSound) }
    var noiseGate by remember { mutableStateOf(settings.noiseGate) }
    var gateDb by remember { mutableStateOf(settings.gateDb.toString()) }
    var gain by remember { mutableStateOf(settings.playbackGain.toString()) }
    val cams = remember {
        List(Settings.CAMERA_COUNT) { mutableStateOf(settings.camera(it)) }
    }
    var testResult by remember { mutableStateOf("") }

    fun save() {
        settings.babyfoonHost = host
        settings.babyfoonPort = port.toIntOrNull() ?: 8080
        settings.thresholdDb = (threshold.toIntOrNull() ?: -35).coerceIn(-90, 0)
        settings.alarmAfterSec = (alarmAfter.toIntOrNull() ?: 10).coerceIn(5, 120)
        settings.notifyOnSound = notifySound
        settings.noiseGate = noiseGate
        settings.gateDb = (gateDb.toIntOrNull() ?: -40).coerceIn(-90, 0)
        settings.playbackGain = (gain.toIntOrNull() ?: 4).coerceIn(1, 16)
        cams.forEachIndexed { i, c -> settings.saveCamera(i, c.value) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
                navigationIcon = {
                    IconButton(onClick = { save(); onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Opslaan en terug")
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Babyfoon", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("IP-adres (bijv. 192.168.1.50)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Poort") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = threshold, onValueChange = { threshold = it.filter { ch -> ch.isDigit() || ch == '-' } },
                label = { Text("Drempel melding in dB (bijv. -35, hoger = minder gevoelig)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = alarmAfter, onValueChange = { alarmAfter = it.filter(Char::isDigit) },
                label = { Text("Alarm na zoveel seconden zonder verbinding") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Melding bij geluid", Modifier.weight(1f))
                Switch(checked = notifySound, onCheckedChange = { notifySound = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ruisonderdrukking (stil als het stil is)", Modifier.weight(1f))
                Switch(checked = noiseGate, onCheckedChange = { noiseGate = it })
            }
            if (noiseGate) {
                OutlinedTextField(
                    value = gateDb, onValueChange = { gateDb = it.filter { ch -> ch.isDigit() || ch == '-' } },
                    label = { Text("Ruisgrens in dB (bijv. -40, iets boven het stille niveau)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = gain, onValueChange = { gain = it.filter(Char::isDigit) },
                label = { Text("Versterking afspelen (1 tot 16, standaard 4)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    testResult = "Bezig…"
                    scope.launch { testResult = testBabyfoon(host, port.toIntOrNull() ?: 8080) }
                }) { Text("Test babyfoon") }
                Text(testResult, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            cams.forEachIndexed { i, state ->
                CameraFields(i, state.value) { state.value = it }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }

            Text("Telefoon", style = MaterialTheme.typography.titleMedium)
            Text(
                "Zet batterijbesparing uit voor deze app, anders kan Android de babyfoon 's nachts stoppen.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { requestNoBatteryOptimization(context) }) {
                Text("Batterijbesparing uitzetten")
            }

            Button(onClick = { save(); onDone() }, modifier = Modifier.fillMaxWidth()) {
                Text("Opslaan")
            }
            Text(
                "App-versie ${nl.thuis.babyfoon.APP_VERSION}",
                style = MaterialTheme.typography.bodySmall,
                color = androidx.compose.ui.graphics.Color.Gray,
            )
        }
    }
}

@Composable
private fun CameraFields(index: Int, c: CameraConfig, onChange: (CameraConfig) -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }

    Text("Camera ${index + 1}", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = c.name, onValueChange = { onChange(c.copy(name = it)) },
        label = { Text("Naam") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = c.host, onValueChange = { onChange(c.copy(host = it)) },
        label = { Text("IP-adres") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    OutlinedTextField(
        value = c.user, onValueChange = { onChange(c.copy(user = it)) },
        label = { Text("Gebruikersnaam") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = c.password, onValueChange = { onChange(c.copy(password = it)) },
        label = { Text("Wachtwoord") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = c.ptz, onCheckedChange = { onChange(c.copy(ptz = it)) })
        Text("Draaien en zoomen (E1 Zoom)")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            result = "Bezig…"
            scope.launch {
                result = Reolink.test(c)
            }
        }) { Text("Test camera") }
        Text(result, style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun testBabyfoon(host: String, port: Int): String = withContext(Dispatchers.IO) {
    if (host.isBlank()) return@withContext "Vul eerst het IP-adres in"
    try {
        val conn = URL("http://${host.trim()}:$port/status").openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        try {
            if (conn.responseCode == 200) "Werkt!" else "Antwoord ${conn.responseCode}"
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        "Niet bereikbaar"
    }
}

@SuppressLint("BatteryLife")
private fun requestNoBatteryOptimization(context: android.content.Context) {
    val pm = context.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    context.startActivity(
        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
    )
}
