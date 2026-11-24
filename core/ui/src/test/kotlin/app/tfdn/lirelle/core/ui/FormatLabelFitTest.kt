package app.tfdn.lirelle.core.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** The words of the format label stay whole on a narrow phone, in both languages, at any text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class FormatLabelFitTest(language: String, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w320dp-h640dp", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val labelled = MediaProfile.entries.filter { it.descriptionRes() != null }

    private fun show() {
        compose.setContent {
            LirelleTheme {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    labelled.forEach { FormatLabel(it, Modifier.testTag("label-${it.name}")) }
                }
            }
        }
    }

    @Test
    fun `the words are whole`() {
        show()

        assertThat(compose.assertAllTextFits()).hasSize(labelled.size)
    }

    @Test
    fun `the words of a book read by pages are whole too, in a label as tall`() {
        compose.setContent {
            LirelleTheme {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PagedKind.entries.forEach { PagedLabel(it, Modifier.testTag("paged-${it.name}")) }
                }
            }
        }

        assertThat(compose.assertAllTextFits()).hasSize(PagedKind.entries.size)
        PagedKind.entries.forEach { kind ->
            val bounds = compose.onNodeWithTag("paged-${kind.name}").getUnclippedBoundsInRoot()
            val height = (bounds.bottom - bounds.top).value
            if (fontScale == 1.0f) assertThat(height).isWithin(0.5f).of(28f) else assertThat(height).isAtLeast(28f)
        }
    }

    @Test
    fun `the label is at least 28 dp tall, and exactly that at the default text size`() {
        show()

        labelled.forEach { media ->
            val bounds = compose.onNodeWithTag("label-${media.name}").getUnclippedBoundsInRoot()
            val height = (bounds.bottom - bounds.top).value
            if (fontScale == 1.0f) assertThat(height).isWithin(0.5f).of(28f) else assertThat(height).isAtLeast(28f)
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} x{1}")
        fun cases(): List<Array<Any>> = listOf("en", "fr").flatMap { language ->
            listOf(1.0f, 1.3f, 2.0f).map { scale -> arrayOf<Any>(language, scale) }
        }
    }
}
