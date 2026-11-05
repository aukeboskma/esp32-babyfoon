package nl.thuis.babyfoon

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import nl.thuis.babyfoon.ui.BabyfoonTheme
import nl.thuis.babyfoon.ui.CameraScreen
import nl.thuis.babyfoon.ui.HomeScreen
import nl.thuis.babyfoon.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ListenService.createChannels(this)
        val settings = Settings(this)
        Monitor.update { it.copy(playAudio = settings.playAudio, mutedIds = settings.mutedIds) }
        setContent {
            BabyfoonTheme {
                App(settings)
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object Instellingen : Screen
    data class Camera(val index: Int) : Screen
}

@Composable
private fun App(settings: Settings) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // Elke keer dat instellingen opgeslagen worden, tegels opnieuw laden
    var settingsVersion by remember { mutableStateOf(0) }

    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            settings = settings,
            settingsVersion = settingsVersion,
            onOpenCamera = { screen = Screen.Camera(it) },
            onOpenSettings = { screen = Screen.Instellingen },
        )
        Screen.Instellingen -> SettingsScreen(
            settings = settings,
            onDone = {
                settingsVersion++
                screen = Screen.Home
            },
        )
        is Screen.Camera -> {
            val cam = settings.cameras.getOrNull(s.index)
            if (cam == null) {
                screen = Screen.Home
            } else {
                CameraScreen(config = cam, onBack = { screen = Screen.Home })
            }
        }
    }
}
