package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.assertAllTextFits
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The names of the settings' pages, in both languages: the short ones and the ones that do not fit a bar. */
private val Titles = listOf("Settings", "Downloads", "Téléchargements", "Bandes dessinées", "Lecteur audio", "À propos")

private const val ContentTag = "page-content"

/**
 * The title of a settings page is whole, never cut short nor broken inside a word, on a narrow phone, a phone
 * and a tablet, at the default and the largest text size, with a back arrow and without.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SettingsPageTitleTest(private val width: Int, private val fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "w${width}dp-h800dp-xhdpi", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val back: String get() = context.getString(R.string.settings_back)

    private var title by mutableStateOf(Titles.first())
    private var arrow by mutableStateOf(true)

    private fun show() {
        compose.setContent {
            LirelleTheme {
                SettingsPageFrame(title = title, onBack = if (arrow) ({}) else null) {
                    Box(Modifier.testTag(ContentTag).fillMaxWidth().height(40.dp))
                }
            }
        }
    }

    @Test
    fun `every title is whole and a heading, with an arrow and without`() {
        show()

        for (withArrow in listOf(true, false)) {
            for (name in Titles) {
                title = name
                arrow = withArrow
                compose.waitForIdle()

                val texts = compose.assertAllTextFits()

                assertWithMessage("\"$name\", arrow $withArrow").that(texts.map { it.text }).containsExactly(name)
                assertWithMessage("\"$name\" cut short").that(texts.single().isEllipsized).isFalse()
                assertWithMessage("\"$name\" shown whole").that(texts.single().isWhollyShown).isTrue()
                compose.onNodeWithText(name).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            }
        }
    }

    @Test
    fun `a title that fits is in the bar, beside the arrow`() {
        title = "À propos"
        show()

        val text = compose.assertAllTextFits().single().boundsInRoot
        val arrowBounds = compose.onNodeWithContentDescription(back).fetchSemanticsNode().boundsInRoot

        assertThat(text.center.y).isWithin(2f).of(arrowBounds.center.y)
        assertThat(text.left).isAtLeast(arrowBounds.right)
    }

    @Test
    fun `a title too long for the bar is under it, on the width of the page, over the content`() {
        title = "Téléchargements"
        show()

        val text = compose.assertAllTextFits().single().boundsInRoot
        val arrowBounds = compose.onNodeWithContentDescription(back).fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag(ContentTag).fetchSemanticsNode().boundsInRoot

        // Only the narrow phone at the largest size has no room for it in the bar.
        if (width == 320 && fontScale == 2.0f) {
            assertThat(text.top).isAtLeast(arrowBounds.bottom)
            assertThat(with(compose.density) { text.left.toDp() }.value).isWithin(0.5f).of(16f)
        } else {
            assertThat(text.center.y).isWithin(2f).of(arrowBounds.center.y)
        }
        assertThat(content.top).isAtLeast(text.bottom)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} dp, font scale {1}")
        fun conditions(): List<Array<Any>> =
            listOf(320, 411, 1280).flatMap { width ->
                listOf(1.0f, 2.0f).map { fontScale -> arrayOf<Any>(width, fontScale) }
            }
    }
}

/** What a page gets to lay itself out in, on a tablet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPageScaffoldTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "w1280dp-h800dp-xhdpi", fontScale = 1.0f)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `the scaffold gives its content the whole width, under the bar`() {
        compose.setContent {
            LirelleTheme {
                SettingsPageScaffold(title = "Reading", onBack = {}) {
                    Box(Modifier.testTag(ContentTag).fillMaxWidth().height(40.dp))
                }
            }
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag(ContentTag).fetchSemanticsNode().boundsInRoot
        val title = compose.assertAllTextFits().single().boundsInRoot

        assertThat(content.width).isEqualTo(root.width)
        assertThat(with(compose.density) { content.width.toDp() }.value).isWithin(0.5f).of(1280f)
        assertThat(content.top).isAtLeast(title.bottom)
    }

    @Test
    fun `the frame keeps its content at a width that reads well, centred beside nothing`() {
        compose.setContent {
            LirelleTheme {
                SettingsPageFrame(title = "Comics", onBack = {}) {
                    Box(Modifier.testTag(ContentTag).fillMaxWidth().height(40.dp))
                }
            }
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag(ContentTag).fetchSemanticsNode().boundsInRoot

        assertThat(with(compose.density) { content.width.toDp() }.value).isWithin(0.5f).of(600f)
        assertThat(content.center.x).isWithin(1f).of(root.center.x)
    }

    @Test
    fun `the frame starts its content at the start of a pane`() {
        compose.setContent {
            LirelleTheme {
                SettingsPageFrame(title = "Comics", onBack = null) {
                    Box(Modifier.testTag(ContentTag).fillMaxWidth().height(40.dp))
                }
            }
        }

        val content = compose.onNodeWithTag(ContentTag).fetchSemanticsNode().boundsInRoot

        assertThat(content.left).isEqualTo(0f)
        assertThat(with(compose.density) { content.width.toDp() }.value).isWithin(0.5f).of(600f)
    }
}
