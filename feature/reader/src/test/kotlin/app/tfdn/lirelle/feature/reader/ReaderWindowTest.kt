package app.tfdn.lirelle.feature.reader

import android.provider.Settings
import android.view.KeyEvent
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.feature.reader.bridge.ReaderEngine
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What the window is told, and what the view model would answer. */
private class RecordingHost : ReaderHostEvents {
    val calls = mutableListOf<String>()
    var handlesBack = true
    var takesVolumeKeys = true

    override fun onEngine(engine: ReaderEngine) = Unit
    override fun onResumed() = Unit
    override fun onPaused() = Unit
    override fun onLeave() = Unit
    override fun onBack(): Boolean {
        calls += "onBack"
        return handlesBack
    }
    override fun onSystemDark(dark: Boolean) = Unit
    override fun onWindow(wide: Boolean) = Unit
    override fun onSystemBrightness(level: Float) {
        calls += "onSystemBrightness($level)"
    }
    override fun onVolumeKey(volumeDown: Boolean): Boolean {
        calls += "onVolumeKey($volumeDown)"
        return takesVolumeKeys
    }
}

@RunWith(RobolectricTestRunner::class)
class ReaderWindowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val host = RecordingHost()
    private var left = 0
    private var shown by mutableStateOf(true)

    private val reading by lazy {
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            settings = ReaderSettings(volumeKeys = true),
        )
    }
    private var state by mutableStateOf(ReaderUiState())

    private val window: Window get() = compose.activity.window

    private fun show(initial: ReaderUiState = reading) {
        state = initial
        compose.setContent {
            if (shown) ReaderWindowEffects(state, host, onLeave = { left++ })
        }
        compose.waitForIdle()
    }

    private fun leave() {
        shown = false
        compose.waitForIdle()
    }

    private fun keepsScreenOn() = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    private fun barsShown(): Boolean? =
        ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars())

    private fun press(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0): Boolean =
        compose.runOnUiThread { window.callback.dispatchKeyEvent(KeyEvent(0, 0, action, code, repeat)) }

    // Screen

    @Test
    fun `the screen stays on while the reader is shown, and no longer`() {
        show()
        assertThat(keepsScreenOn()).isTrue()

        leave()
        assertThat(keepsScreenOn()).isFalse()
    }

    @Test
    fun `the system bars are hidden while reading, shown with the controls, and given back on leaving`() {
        show()
        assertThat(barsShown()).isFalse()

        state = reading.copy(controlsShown = true)
        compose.waitForIdle()
        assertThat(barsShown()).isTrue()

        state = reading
        compose.waitForIdle()
        assertThat(barsShown()).isFalse()

        leave()
        assertThat(barsShown()).isTrue()
    }

    @Test
    fun `the system bars stay while the book is fetched or could not be opened`() {
        show(ReaderUiState())
        assertThat(barsShown()).isTrue()

        state = ReaderUiState(phase = ReaderPhase.Failed(ReaderFailure.NoEpub))
        compose.waitForIdle()
        assertThat(barsShown()).isTrue()
    }

    // Brightness

    @Test
    fun `the reader's brightness is the window's, and the one before comes back on leaving`() {
        compose.runOnUiThread { window.attributes = window.attributes.apply { screenBrightness = 0.8f } }
        show(reading.copy(settings = ReaderSettings(brightness = 0.3f)))
        assertThat(window.attributes.screenBrightness).isEqualTo(0.3f)

        state = reading.copy(settings = ReaderSettings(brightness = 0.31f))
        compose.waitForIdle()
        assertThat(window.attributes.screenBrightness).isEqualTo(0.31f)

        leave()
        assertThat(window.attributes.screenBrightness).isEqualTo(0.8f)
    }

    @Test
    fun `without a brightness of its own the reader follows the system`() {
        show(reading.copy(settings = ReaderSettings(brightness = 0.3f)))

        state = reading
        compose.waitForIdle()

        assertThat(window.attributes.screenBrightness).isEqualTo(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    @Test
    fun `a first drag starts from the system's brightness`() {
        Settings.System.putInt(compose.activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 51)

        show()

        assertThat(host.calls).contains("onSystemBrightness(0.2)")
    }

    @Test
    fun `a first drag starts from the window's brightness when it has one`() {
        compose.runOnUiThread { window.attributes = window.attributes.apply { screenBrightness = 0.8f } }

        show(reading.copy(settings = ReaderSettings(brightness = 0.3f)))

        assertThat(host.calls).contains("onSystemBrightness(0.8)")
    }

    // Volume keys

    @Test
    fun `volume keys turn the page, and neither press nor release reaches the volume`() {
        show()

        assertThat(press(KeyEvent.KEYCODE_VOLUME_DOWN)).isTrue()
        assertThat(press(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP)).isTrue()
        assertThat(press(KeyEvent.KEYCODE_VOLUME_UP)).isTrue()
        assertThat(press(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP)).isTrue()

        assertThat(host.calls.filter { it.startsWith("onVolumeKey") })
            .containsExactly("onVolumeKey(true)", "onVolumeKey(false)").inOrder()
    }

    @Test
    fun `a key held down turns one page only, and still does not change the volume`() {
        show()

        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertThat(press(KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 1)).isTrue()

        assertThat(host.calls.count { it.startsWith("onVolumeKey") }).isEqualTo(1)
    }

    @Test
    fun `with the option off the volume keys are the system's`() {
        host.takesVolumeKeys = false
        show(reading.copy(settings = ReaderSettings(volumeKeys = false)))

        assertThat(press(KeyEvent.KEYCODE_VOLUME_DOWN)).isFalse()
        assertThat(press(KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 1)).isFalse()
        assertThat(press(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP)).isFalse()
    }

    @Test
    fun `under a sheet, a panel or a question the volume keys are the system's`() {
        host.takesVolumeKeys = false
        val covered = listOf(
            reading.copy(openSheet = ReaderSheet.Settings),
            reading.copy(endPanel = EndPanel()),
            reading.copy(externalLink = "https://example.org"),
            reading.copy(noteOpen = true),
            reading.copy(highlightPanel = HighlightPanelUi(MarkId("h1"), "text", "#FACC15", "", "", false, false)),
            reading.copy(opened = false),
        )
        show()

        covered.forEach {
            state = it
            compose.waitForIdle()
            assertThat(press(KeyEvent.KEYCODE_VOLUME_UP)).isFalse()
            assertThat(press(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP)).isFalse()
        }
    }

    @Test
    fun `other keys are not the reader's`() {
        show()

        assertThat(press(KeyEvent.KEYCODE_A)).isFalse()
        assertThat(host.calls.filter { it.startsWith("onVolumeKey") }).isEmpty()
    }

    @Test
    fun `once the reader is left the keys are the window's again`() {
        val before = window.callback
        show()
        assertThat(window.callback).isNotSameInstanceAs(before)

        leave()

        assertThat(window.callback).isSameInstanceAs(before)
        assertThat(press(KeyEvent.KEYCODE_VOLUME_DOWN)).isFalse()
    }

    // Back

    @Test
    fun `back first closes what the reader has open`() {
        show()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertThat(host.calls).contains("onBack")
        assertThat(left).isEqualTo(0)
    }

    @Test
    fun `back leaves the reader when nothing is open`() {
        host.handlesBack = false
        show()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertThat(left).isEqualTo(1)
    }
}
