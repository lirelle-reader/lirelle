package app.tfdn.lirelle.feature.comics.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Width = 200
private const val Height = 300
private const val Page = 5

/** When the kept page was written, and when the one the update fetched was. */
private const val KeptAt = 1_700_000_000_000L
private const val UpdatedAt = KeptAt + 60_000L

/**
 * A page the downloads keep has the same file before and after an update of its album: the newer picture is
 * moved into the place of the older one. The reader opened again draws what the file holds now, not what the
 * image loader remembers of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ComicPageReplacedPictureTest {

    @get:Rule(order = 0)
    val images = PageImages()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private var open by mutableStateOf(true)
    private var page: PageState? by mutableStateOf(null)

    /** The colour in the middle of the page view. */
    private fun middle(): Color {
        val pixels = compose.onNodeWithTag(ComicSpreadTag).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    /** A picture of the [colour] in the kept place, moved there in one step as an update's is, written [at]. */
    private fun keep(place: File, colour: Int, at: Long): PageState.Ready {
        val fetched = pageImage(folder.newFolder(), Page, Width, Height, colour)
        fetched.file.setLastModified(at)
        Files.move(fetched.file.toPath(), place.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return fetched.copy(file = place)
    }

    @Test
    fun `a kept page replaced by an update is drawn as it is now when the reader opens again`() {
        val place = File(folder.newFolder("pages"), "$Page")
        page = keep(place, android.graphics.Color.RED, KeptAt)
        compose.setContent {
            val shown = page
            if (open && shown != null) {
                val settings = ComicSettings(background = Background.White)
                val state = album(spreadIndex = Page, settings = settings, pages = mapOf(Page to shown))
                images.Provide { LirelleTheme { ComicSpreadView(state, RecordingNavigation(), onToggleBars = {}) } }
            }
        }
        compose.waitForIdle()
        assertThat(middle().red).isGreaterThan(0.8f)

        // The reader is left, the update takes its place, the reader is opened again.
        open = false
        compose.waitForIdle()
        page = keep(place, android.graphics.Color.BLUE, UpdatedAt)
        open = true
        compose.waitForIdle()

        assertThat(middle().blue).isGreaterThan(0.8f)
        assertThat(middle().red).isLessThan(0.2f)
    }
}
