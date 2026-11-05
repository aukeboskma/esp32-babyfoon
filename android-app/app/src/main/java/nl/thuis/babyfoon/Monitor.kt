package nl.thuis.babyfoon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Houd gelijk met versionName in app/build.gradle.kts */
const val APP_VERSION = "1.4.0"

enum class LinkStatus { UIT, VERBINDEN, VERBONDEN, KWIJT }

data class MonitorUi(
    val running: Boolean = false,
    val status: LinkStatus = LinkStatus.UIT,
    val levelDb: Float = -90f,
    val soundActive: Boolean = false,
    val gateOpen: Boolean = true,
    val alarm: Boolean = false,
    val alarmAcknowledged: Boolean = false,
    val playAudio: Boolean = true,
    val message: String = "",
    val talking: Boolean = false,
    val talkError: String = "",
)

/** Gedeelde toestand tussen de achtergronddienst en het scherm. */
object Monitor {
    private val _state = MutableStateFlow(MonitorUi())
    val state: StateFlow<MonitorUi> = _state

    fun update(change: (MonitorUi) -> MonitorUi) = _state.update(change)
}
