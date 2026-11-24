package app.tfdn.lirelle.feature.player

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * Every control of the player is shown whole, at its full size, without scrolling, on phones and
 * tablets in either orientation, at the default and a large text size, "Go back" shown or not.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the texts above the controls take their real height.
class PlayerLayoutTest(
    private val qualifiers: String,
    fontScale: Float,
    private val goBack: Boolean,
    private val chaptersColumn: Boolean,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = qualifiers, fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.assertWhole(size: Dp, name: String) {
        assertIsDisplayed()
        val node = fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val expected = with(compose.density) { size.toPx() }
        assertWithMessage("width of $name").that(node.boundsInRoot.width).isWithin(1f).of(expected)
        assertWithMessage("height of $name").that(node.boundsInRoot.height).isWithin(1f).of(expected)
        assertWithMessage("$name inside the window").that(root.contains(node.boundsInRoot.topLeft)).isTrue()
        assertWithMessage("$name inside the window").that(node.boundsInRoot.right).isAtMost(root.right)
        assertWithMessage("$name inside the window").that(node.boundsInRoot.bottom).isAtMost(root.bottom)
    }

    @Test
    fun `every control is shown whole`() {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(PlayerSamples.playing.copy(goBackShown = goBack), RecordingActions(), {}, {}, {})
            }
        }

        compose.onNodeWithContentDescription("Previous chapter").assertWhole(ChapterButtonSize, "previous")
        compose.onNodeWithContentDescription("Back 15 seconds").assertWhole(SkipButtonSize, "back")
        compose.onNodeWithContentDescription("Pause").assertWhole(PlayButtonSize, "play")
        compose.onNodeWithContentDescription("Forward 30 seconds").assertWhole(SkipButtonSize, "forward")
        compose.onNodeWithContentDescription("Next chapter").assertWhole(ChapterButtonSize, "next")
        val column = compose.onAllNodes(hasTestTag(ChaptersColumnTag)).fetchSemanticsNodes()
        assertThat(column.isNotEmpty()).isEqualTo(chaptersColumn)
        if (goBack) compose.onAllNodes(hasTestTag(GoBackTag)).assertCountEquals(1)
    }

    @Test
    fun `the cover of a large upright window grows, and the player is centred above the bottom row`() {
        if (qualifiers != TabletPortrait) return
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(PlayerSamples.playing.copy(goBackShown = goBack), RecordingActions(), {}, {}, {})
            }
        }
        val cover = compose.onNodeWithTag(PlayerCoverTag).fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithContentDescription("Pause").fetchSemanticsNode().boundsInRoot
        val barBottom = compose.onNodeWithTag(PlayerMenuTag).fetchSemanticsNode().boundsInRoot.bottom
        val rowTop = compose.onNodeWithTag(SpeedButtonTag).fetchSemanticsNode().boundsInRoot.top

        with(compose.density) {
            assertThat(cover.width.toDp().value).isGreaterThan(264f)
            val above = (cover.top - barBottom).toDp().value
            val below = (rowTop - controls.bottom).toDp().value
            assertWithMessage("room above the cover ($above dp) and below the controls ($below dp)")
                .that(above).isWithin(CentredWithinDp).of(below)
        }
    }

    @Test
    fun `go back over a cover is no wider than the cover`() {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                PlayerContent(PlayerSamples.playing.copy(goBackShown = goBack), RecordingActions(), {}, {}, {})
            }
        }
        val cover = compose.onAllNodes(hasTestTag(PlayerCoverTag)).fetchSemanticsNodes().singleOrNull() ?: return
        val back = compose.onAllNodes(hasTestTag(GoBackTag)).fetchSemanticsNodes().singleOrNull() ?: return
        // Over the cover, in a turned window; under the texts, in an upright one.
        if (back.boundsInRoot.top < cover.boundsInRoot.bottom) {
            assertThat(back.boundsInRoot.left).isAtLeast(cover.boundsInRoot.left - 1f)
            assertThat(back.boundsInRoot.right).isAtMost(cover.boundsInRoot.right + 1f)
        }
    }

    companion object {
        private const val TabletPortrait = "w840dp-h1340dp"

        /** The padding above the column and the gap above the bottom row differ by this much at most. */
        private const val CentredWithinDp = 12f

        @JvmStatic
        @Parameters(name = "{0} x{1} go back {2}")
        fun cases(): List<Array<Any>> = listOf(
            Triple("w891dp-h411dp", 1f, false),
            Triple("w880dp-h600dp", 1f, false),
            Triple("w1280dp-h800dp", 1f, true),
            Triple("w411dp-h891dp", 1f, false),
            Triple("w960dp-h600dp", 1f, false),
            Triple("w960dp-h600dp", 2f, false),
            Triple("w1000dp-h600dp", 1f, false),
            Triple("w1000dp-h600dp", 2f, false),
            Triple("w411dp-h400dp", 1f, false),
            Triple("w411dp-h400dp", 2f, false),
            Triple("w640dp-h360dp", 2f, false),
            Triple(TabletPortrait, 1f, false),
            Triple(TabletPortrait, 2f, false),
        ).flatMap { (screen, scale, chapters) ->
            listOf(false, true).map { goBack -> arrayOf<Any>(screen, scale, goBack, chapters) }
        }
    }
}
