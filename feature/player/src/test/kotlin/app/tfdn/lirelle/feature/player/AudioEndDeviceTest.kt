package app.tfdn.lirelle.feature.player

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.ui.fileSize
import app.tfdn.lirelle.feature.player.controller.FinishedState
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The row of the audio end panel for what the device keeps of the book. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AudioEndDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val actions = RecordingActions()
    private val formats = listOf(DownloadFormat.Text, DownloadFormat.Audiobook)
    private val kept = DeviceCopy(formats, 1_200_000_000, deleting = false)

    private fun show(device: DeviceCopy?, sending: Boolean = false) {
        val next = NextVolume(BookId("43"), "The Long Road Back", "2")
        val finished = FinishedState(rating = 4, next, sending = sending, failed = false, panelSeen = false, device)
        val state = PlayerSamples.playing.copy(playing = false, finished = finished)
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(state, actions, onMinimise = {}, onOpenBook = {}, onOpenSettings = {})
            }
        }
    }

    private fun text(id: Int) = context.getString(id)

    private fun delete() = compose.onNodeWithText(text(R.string.player_end_device_delete))

    private fun undo() = compose.onNodeWithText(text(R.string.player_end_device_undo))

    @Test
    fun `a book the device does not keep has no row`() {
        show(device = null)

        compose.onNodeWithText(text(R.string.player_end_device)).assertDoesNotExist()
        delete().assertDoesNotExist()
        undo().assertDoesNotExist()
    }

    @Test
    fun `the row says what the device keeps and how much, and delete asks for it`() {
        show(kept)

        compose.onNodeWithText(text(R.string.player_end_device)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Text and audiobook · ${context.fileSize(1_200_000_000)}").assertIsDisplayed()
        undo().assertDoesNotExist()
        delete().performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("deleteFromDevice")
    }

    @Test
    fun `one format is named alone`() {
        show(DeviceCopy(listOf(DownloadFormat.Audiobook), 412_000_000, deleting = false))

        compose.onNodeWithText("Audiobook · ${context.fileSize(412_000_000)}").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a PDF kept with its audiobook is named with it`() {
        show(kept.copy(textName = TextName.Pdf))

        compose.onNodeWithText("PDF and audiobook · ${context.fileSize(1_200_000_000)}")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `every format of a comic is one phrase`() {
        show(kept.copy(formats = DownloadFormat.entries, textName = TextName.Cbz))

        compose.onNodeWithText("CBZ, audiobook and narration · ${context.fileSize(1_200_000_000)}")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `a PDF kept with its audiobook, in French`() {
        show(kept.copy(textName = TextName.Pdf))

        compose.onNodeWithText("PDF et livre audio · ${context.fileSize(1_200_000_000)}")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the row, in French`() {
        show(kept)

        compose.onNodeWithText("Sur cet appareil").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Texte et livre audio · ${context.fileSize(1_200_000_000)}").assertIsDisplayed()
        compose.onNodeWithText("Supprimer").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a book that goes when it closes says so, and undo keeps it`() {
        show(kept.copy(deleting = true))

        compose.onNodeWithText(text(R.string.player_end_device_deleted)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.player_end_device)).assertDoesNotExist()
        delete().assertDoesNotExist()
        undo().performScrollTo().performClick()

        assertThat(actions.asked).containsExactly("keepOnDevice")
    }

    @Test
    fun `the row waits with the rest while the rating is on its way`() {
        show(kept, sending = true)

        delete().performScrollTo().assertIsNotEnabled().performClick()

        assertThat(actions.asked).isEmpty()
    }

    @Test
    fun `its button is at least 48 dp on each side`() {
        show(kept)

        val bounds = delete().performScrollTo().getUnclippedBoundsInRoot()
        assertThat(bounds.width.value).isAtLeast(MinTouchDp)
        assertThat(bounds.height.value).isAtLeast(MinTouchDp)
    }

    private companion object {
        const val MinTouchDp = 48f
    }
}
