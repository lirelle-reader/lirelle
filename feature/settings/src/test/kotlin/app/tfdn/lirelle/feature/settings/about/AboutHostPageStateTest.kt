package app.tfdn.lirelle.feature.settings.about

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val LongId = "library:long"
private const val LongName = "Long Terms"

/** How far a licence's text was scrolled, through a change of width: it is the same text, alone or in the pane. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class AboutHostPageStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var widthClass by mutableStateOf(WindowWidthClass.Expanded)

    /** One library, under a licence whose text is far longer than the screen. */
    private val licences = Licences(
        bundled = emptyList(),
        libraries = listOf(
            Component(
                id = LongId,
                name = LongName,
                author = "Ann Author",
                bundled = false,
                licences = listOf(Licence("Terms", "Terms", (1..400).joinToString("\n") { "Line $it of the terms." })),
            ),
        ),
    )

    private fun show() = compose.setContent {
        LirelleTheme {
            CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                AboutContent(appVersion = "0.1.42", licences = licences, onBack = {})
            }
        }
    }

    /** How far the text is scrolled: the furthest of what scrolls, About beside it being at its top. */
    private fun scrolled(): Float = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() }
        .max()

    private fun scrollTheText(): Float {
        compose.onNodeWithText("Line 1 of the terms.", substring = true).performTouchInput { swipeUp() }
        compose.waitForIdle()
        return scrolled().also { assertThat(it).isGreaterThan(0f) }
    }

    @Test
    fun `a text in the pane stays where it was scrolled when the window turns narrow, and wide again`() {
        show()
        compose.onNodeWithTag(licenceRowTag(LongId)).performClick()
        val before = scrollTheText()

        widthClass = WindowWidthClass.Compact
        compose.waitForIdle()
        assertThat(scrolled()).isEqualTo(before)

        widthClass = WindowWidthClass.Expanded
        compose.waitForIdle()
        assertThat(scrolled()).isEqualTo(before)
    }

    @Test
    fun `a text read alone stays where it was scrolled when the window turns wide, and narrow again`() {
        widthClass = WindowWidthClass.Medium
        show()
        compose.onNodeWithTag(AboutLicencesRowTag).performClick()
        compose.onNodeWithTag(licenceRowTag(LongId)).performClick()
        val before = scrollTheText()

        widthClass = WindowWidthClass.Expanded
        compose.waitForIdle()
        assertThat(scrolled()).isEqualTo(before)

        widthClass = WindowWidthClass.Medium
        compose.waitForIdle()
        assertThat(scrolled()).isEqualTo(before)
    }
}
