package app.tfdn.lirelle.feature.player.narration

import androidx.compose.ui.graphics.Color
import app.tfdn.lirelle.core.audio.timer.SleepChoice

/** The light reading theme of the mock-ups. */
val LightNarrationColors = NarrationColors(
    background = Color(0xFFEFEBE1),
    foreground = Color(0xFF1B1E2E),
    muted = Color(0xFF575B6C),
    accent = Color(0xFF1D4FA8),
    onAccent = Color.White,
    track = Color(0xFFD9D3C4),
    outline = Color(0xFFC9C2B2),
)

/** What the narration's pieces asked for, each as a short line. */
class RecordingNarrationActions : NarrationActions {
    val asked = mutableListOf<String>()

    override fun playPause() {
        asked += "playPause"
    }

    override fun previousSentence() {
        asked += "previousSentence"
    }

    override fun nextSentence() {
        asked += "nextSentence"
    }

    override fun back() {
        asked += "back"
    }

    override fun forward() {
        asked += "forward"
    }

    override fun seekInSection(fraction: Float) {
        asked += "seekInSection"
    }

    override fun goToSection(index: Int) {
        asked += "goToSection $index"
    }

    override fun goBack() {
        asked += "goBack"
    }

    override fun setSpeed(speed: Float) {
        asked += "setSpeed $speed"
    }

    override fun startSleep(choice: SleepChoice) {
        asked += "startSleep $choice"
    }

    override fun cancelSleep() {
        asked += "cancelSleep"
    }

    override fun stop() {
        asked += "stop"
    }

    override fun setShakeToExtend(enabled: Boolean) {
        asked += "setShakeToExtend $enabled"
    }
}
