package app.tfdn.lirelle.feature.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.player.controller.FinishedState
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the mini-player or of the player settings overflows or is clipped, narrow and large or ordinary. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class MiniPlayerTextFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val longest = PlayerSamples.playing.copy(
        title = LongTitle,
        chapterTitle = LongChapter,
        chapterElapsedMs = 0,
        chapterDurationMs = 10 * 60 * 60_000L,
        settings = PlayerSettings(backSeconds = 60),
    )

    @Test
    fun `the mini-player with everything long`() {
        showMini(longest)

        // The title and the chapter's line keep to one line, cut short.
        val texts = compose.assertAllTextFits()
        val title = texts.single { it.text == LongTitle }
        assertWithMessage("lines of $title").that(title.lineCount).isEqualTo(1)
        assertWithMessage("$title is cut short").that(title.isEllipsized).isTrue()
    }

    @Test
    fun `the mini-player with everything long, at 0,5x`() {
        showMini(longest.copy(speed = 0.5f))
        compose.assertAllTextFits()
    }

    @Test
    fun `the mini-player with a sleep timer`() {
        showMini(longest.copy(sleep = SleepState(SleepChoice.Minutes(45), 44 * 60_000L, fading = false)))

        compose.assertAllTextFits()
    }

    @Test
    fun `the mini-player of a finished book`() {
        val finished =
            FinishedState(rating = null, nextVolume = null, sending = false, failed = false, panelSeen = true)
        showMini(longest.copy(playing = false, finished = finished))

        compose.assertAllTextFits()
    }

    @Test
    fun `the mini-player of a narration whose section has a long title`() {
        showMini(longest.copy(narrationFileId = FileId("1")), PlayerSamples.narration.copy(sectionTitle = LongChapter))

        val texts = compose.assertAllTextFits()
        val line = texts.single { it.text.endsWith(LongChapter) }
        assertWithMessage("lines of $line").that(line.lineCount).isEqualTo(1)
        assertWithMessage("$line is cut short").that(line.isEllipsized).isTrue()
    }

    @Test
    fun `the mini-player of a finished narration`() {
        showMini(
            longest.copy(narrationFileId = FileId("1"), playing = false),
            PlayerSamples.narration.copy(finished = true),
        )

        compose.assertAllTextFits()
    }

    @Test
    fun `the snackbar of a stopped listening, with its undo`() {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                val snackbars = remember { SnackbarHostState() }
                val message = stringResource(R.string.player_stopped)
                val undo = stringResource(R.string.player_stopped_undo)
                LaunchedEffect(Unit) {
                    snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Indefinite)
                }
                Column {
                    SnackbarHost(snackbars)
                    MiniPlayerContent(longest, onOpenPlayer = {}, onPlayPause = {}, onBack = {}, onRate = {})
                }
            }
        }

        val texts = compose.assertAllTextFits()
        val shown = texts.map { it.text }
        assertWithMessage("the snackbar's texts").that(shown).containsAtLeast(
            compose.activity.getString(R.string.player_stopped),
            compose.activity.getString(R.string.player_stopped_undo),
        )
    }

    @Test
    fun `the player settings`() {
        compose.setContent {
            LirelleTheme {
                PlayerSettingsContent(PlayerSettings(), NoChanges, onBack = {})
            }
        }

        compose.assertAllTextFits()
    }

    private fun showMini(state: PlayerUiState, narration: NarrationUi? = null) {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                MiniPlayerContent(
                    state,
                    onOpenPlayer = {},
                    onPlayPause = {},
                    onBack = {},
                    onRate = {},
                    narration = narration,
                    onOpenReader = {},
                )
            }
        }
    }

    private object NoChanges : PlayerSettingsChanges {
        override fun setBackSeconds(seconds: Int) = Unit

        override fun setForwardSeconds(seconds: Int) = Unit

        override fun setRewindOnResume(enabled: Boolean) = Unit

        override fun setSleepFade(enabled: Boolean) = Unit

        override fun setShakeToExtend(enabled: Boolean) = Unit
    }

    companion object {
        private const val LongTitle =
            "A title long enough to need several lines on a narrow phone at a large text size, and then some more"
        private const val LongChapter =
            "The chapter whose title is a whole sentence about the crossing of the strait at night in the storm"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("w320dp-h640dp", "fr", 2.0f),
            arrayOf("w411dp-h891dp", "en", 1.0f),
        )
    }
}
