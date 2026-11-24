package app.tfdn.lirelle.feature.downloads

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ui.fileSize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class WifiBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(banner: WifiBannerUi) {
        compose.setContent { LirelleTheme { WifiBanner(banner, onDownloadNow = {}, onAlwaysAllowMobile = {}) } }
    }

    @Test
    fun `one book waiting says its number and how much is left`() {
        show(WifiBannerUi(bookCount = 1, leftBytes = 198 * Mb))

        val size = context.fileSize(198 * Mb)
        val sentence = context.resources.getQuantityString(R.plurals.downloads_wifi_left, 1, 1, size)
        assertThat(sentence).startsWith("1 book, $size left.")
        compose.onNodeWithText(sentence).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `the same in French`() {
        show(WifiBannerUi(bookCount = 1, leftBytes = 198 * Mb))

        val size = context.fileSize(198 * Mb)
        val sentence = context.resources.getQuantityString(R.plurals.downloads_wifi_left, 1, 1, size)
        assertThat(sentence).startsWith("1 livre, $size restants.")
        compose.onNodeWithText(sentence).assertIsDisplayed()
    }

    @Test
    fun `several books waiting are counted`() {
        show(WifiBannerUi(bookCount = 3, leftBytes = 666 * Mb))

        val size = context.fileSize(666 * Mb)
        val sentence = context.resources.getQuantityString(R.plurals.downloads_wifi_left, 3, 3, size)
        assertThat(sentence).startsWith("3 books, $size left.")
        compose.onNodeWithText(sentence).assertIsDisplayed()
    }

    @Test
    fun `without a known size the sentence has none`() {
        show(WifiBannerUi(bookCount = 1, leftBytes = null))

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.downloads_wifi_waiting, 1, 1))
            .assertIsDisplayed()
    }
}
