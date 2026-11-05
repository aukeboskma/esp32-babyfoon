package nl.thuis.babyfoon

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Houd gelijk met versionName in app/build.gradle.kts */
const val APP_VERSION = "2.1.0"

enum class LinkStatus { UIT, VERBINDEN, VERBONDEN, KWIJT }

/** Toestand van één babyfoon. */
data class BabyState(
    val id: String,
    val name: String,
    val status: LinkStatus = LinkStatus.VERBINDEN,
    val levelDb: Float = -90f,
    val soundActive: Boolean = false,
    val gateOpen: Boolean = true,
    val message: String = "",
    /** Drempels zoals ingesteld op het bordje (null = app-instelling gebruiken). */
    val boardSoundThreshold: Float? = null,
    val boardGateThreshold: Float? = null,
    val boardVersion: String = "",
    val canTune: Boolean = false,
    val tuneMessage: String = "",
)

data class MonitorUi(
    val running: Boolean = false,
    /** Per babyfoon, in de volgorde van de instellingen. */
    val babies: List<BabyState> = emptyList(),
    val alarm: Boolean = false,
    val alarmAcknowledged: Boolean = false,
    /** Namen van de babyfoons zonder verbinding. */
    val alarmNames: List<String> = emptyList(),
    val playAudio: Boolean = true,
    val mutedIds: Set<String> = emptySet(),
    /** Id van de babyfoon waartegen je nu praat, of null. */
    val talkingId: String? = null,
    val talkError: String = "",
) {
    val talking: Boolean get() = talkingId != null
}

/** Gedeelde toestand tussen de achtergronddienst en het scherm. */
object Monitor {
    private val _state = MutableStateFlow(MonitorUi())
    val state: StateFlow<MonitorUi> = _state

    fun update(change: (MonitorUi) -> MonitorUi) = _state.update(change)

    fun updateBaby(id: String, change: (BabyState) -> BabyState) = _state.update { ui ->
        ui.copy(babies = ui.babies.map { if (it.id == id) change(it) else it })
    }
}
