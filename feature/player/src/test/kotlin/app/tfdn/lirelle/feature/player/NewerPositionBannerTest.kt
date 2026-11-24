package app.tfdn.lirelle.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.feature.player.controller.OfferedChapter
import app.tfdn.lirelle.feature.player.controller.OfferedListening
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sentence of a newer position: its place, and how long ago it was written, in the middle of it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class NewerPositionBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = Instant.parse("2026-11-15T12:00:00Z")

    /** 1:23:04 into the book, 2:00 into its chapter. */
    private fun offered(ago: Duration, chapter: OfferedChapter? = null) =
        OfferedListening((60 * 60 + 23 * 60 + 4) * 1_000L, now - ago, chapter)

    private fun show(offered: OfferedListening) = compose.setContent {
        LirelleTheme { NewerPositionBanner(offered, now, onAccept = {}, onIgnore = {}) }
    }

    @Test
    fun `written seconds ago, it was written just now`() {
        show(offered(Duration.ofSeconds(20)))

        compose.onNodeWithText("Go to 1:23:04 (just now)").assertIsDisplayed()
    }

    @Test
    fun `written a little ahead of the device's clock, it was written just now too`() {
        show(offered(Duration.ofSeconds(-30)))

        compose.onNodeWithText("Go to 1:23:04 (just now)").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, the time ago starts in lower case inside the sentence`() {
        show(offered(Duration.ofHours(2)))

        compose.onNodeWithText("Aller à 1:23:04 (il y a 2 heures)").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, just now starts in lower case too`() {
        show(offered(Duration.ofSeconds(5)))

        compose.onNodeWithText("Aller à 1:23:04 (à l’instant)").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in a book of parts, the part and the time in it are the place`() {
        show(offered(Duration.ofSeconds(5), OfferedChapter(100, "Partie 100", 120_000)))

        compose.onNodeWithText("Aller à Partie 100 · 2:00 (à l’instant)").assertIsDisplayed()
    }

    @Test
    fun `in a book with chapters of its own, the time in the book is the place`() {
        show(offered(Duration.ofHours(2), OfferedChapter(3, "The Return of the Long Road", 120_000)))

        compose.onNodeWithText("Go to 1:23:04 (2 hours ago)").assertIsDisplayed()
    }
}
