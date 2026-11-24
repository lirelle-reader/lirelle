package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class FormatBadgeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `text-only books have no badge and no label`() {
        compose.setContent {
            LirelleTheme {
                FormatBadge(MediaProfile.TextOnly)
                FormatLabel(MediaProfile.TextOnly)
            }
        }

        assertThat(compose.onAllNodesWithTag(FormatBadgeTag).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `each other state is announced in words`() {
        mapOf(
            MediaProfile.AudioOnly to R.string.media_audio_only,
            MediaProfile.TextAndAudio to R.string.media_text_and_audio,
            MediaProfile.ReadAlong to R.string.media_read_along,
        ).forEach { (media, text) ->
            assertThat(media.descriptionRes()).isEqualTo(text)
        }
        assertThat(MediaProfile.TextOnly.descriptionRes()).isNull()
    }

    @Test
    fun `the badge carries the description`() {
        compose.setContent { LirelleTheme { FormatBadge(MediaProfile.ReadAlong) } }

        compose.onNodeWithContentDescription(context.getString(R.string.media_read_along)).assertIsDisplayed()
    }

    @Test
    fun `the label shows the words`() {
        compose.setContent { LirelleTheme { FormatLabel(MediaProfile.TextAndAudio) } }

        compose.onNodeWithText(context.getString(R.string.media_text_and_audio)).assertIsDisplayed()
    }

    @Test
    fun `the label of a book read by pages says in words what its pages are`() {
        var kind by mutableStateOf(PagedKind.Comic)
        compose.setContent { LirelleTheme { PagedLabel(kind) } }

        mapOf(PagedKind.Comic to "Comic", PagedKind.Pdf to "PDF", PagedKind.Djvu to "DjVu").forEach { (shown, words) ->
            kind = shown
            compose.waitForIdle()

            compose.onNodeWithText(words).assertIsDisplayed()
            assertThat(compose.onAllNodesWithTag(PagedLabelTag).fetchSemanticsNodes()).hasSize(1)
            assertThat(compose.onAllNodesWithTag(FormatBadgeTag).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French a comic is a BD, and a PDF and a DjVu keep their names`() {
        var kind by mutableStateOf(PagedKind.Comic)
        compose.setContent { LirelleTheme { PagedLabel(kind) } }

        mapOf(PagedKind.Comic to "BD", PagedKind.Pdf to "PDF", PagedKind.Djvu to "DjVu").forEach { (shown, words) ->
            kind = shown
            compose.waitForIdle()

            compose.onNodeWithText(words).assertIsDisplayed()
        }
    }

    @Test
    fun `each kind of pages has its words and its glyph, the comic's being the panels of a page`() {
        assertThat(PagedKind.entries.map { it.labelRes() }.toSet()).hasSize(PagedKind.entries.size)
        assertThat(PagedKind.entries.map { it.glyph() }.toSet()).hasSize(PagedKind.entries.size)
        assertThat(PagedKind.Comic.glyph()).isEqualTo(Icons.Outlined.Dashboard)
    }
}
