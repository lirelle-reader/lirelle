package app.tfdn.lirelle.feature.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The chapter's bar: where it is drawn while dragged and once let go, what it seeks, what a screen reader hears. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ChapterBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val sought = mutableListOf<Float>()
    private var place by mutableStateOf(BarPlace(chapterIndex = 1, elapsedMs = 10_000, durationMs = 40_000, speed = 1f))

    private fun show() {
        compose.setContent { LirelleTheme(darkTheme = true) { ChapterBar(place, onSeek = { sought += it }) } }
    }

    private val bar get() = compose.onNodeWithTag(ChapterBarTag)

    private fun drawn(): Float = bar.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun spoken(): String? = bar.fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private fun drag() = bar.performTouchInput {
        down(center)
        repeat(3) { moveBy(Offset(width / 10f, 0f)) }
    }

    @Test
    fun `let go, the bar stays where it was let go until playback is there`() {
        show()
        drag()
        bar.performTouchInput { up() }
        val target = sought.single()

        // Playback has not moved yet: the bar does not jump back.
        place = place.copy(elapsedMs = 10_500)
        compose.waitForIdle()
        assertThat(drawn()).isWithin(0.001f).of(target)

        // Playback is there: the bar follows it again.
        place = place.copy(elapsedMs = (40_000 * target).toLong() + 200)
        compose.waitForIdle()
        place = place.copy(elapsedMs = 20_000)
        compose.waitForIdle()
        assertThat(drawn()).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `a seek that never shows does not hold the bar for ever`() {
        show()
        drag()
        bar.performTouchInput { up() }

        repeat(4) { step ->
            place = place.copy(elapsedMs = 10_500L + step * 500)
            compose.waitForIdle()
        }

        assertThat(drawn()).isWithin(0.001f).of(12_000 / 40_000f)
    }

    @Test
    fun `a paused player sends no news, so the bar let go follows it again after a while`() {
        show()
        compose.mainClock.autoAdvance = false
        drag()
        bar.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertThat(drawn()).isWithin(0.001f).of(sought.single())

        compose.mainClock.advanceTimeBy(2_500)

        assertThat(drawn()).isWithin(0.001f).of(0.25f)
    }

    @Test
    fun `a drag across a chapter change is cancelled and seeks nothing`() {
        show()
        drag()

        place = BarPlace(chapterIndex = 2, elapsedMs = 0, durationMs = 60_000, speed = 1f)
        compose.waitForIdle()
        assertThat(drawn()).isWithin(0.001f).of(0f)
        bar.performTouchInput {
            moveBy(Offset(width / 10f, 0f))
            up()
        }

        assertThat(sought).isEmpty()
        assertThat(drawn()).isWithin(0.001f).of(0f)
    }

    @Test
    fun `a screen reader hears the time played and the chapter's length`() {
        show()

        assertThat(spoken()).isEqualTo("0:10 of 0:40")
        place = place.copy(durationMs = null)
        compose.waitForIdle()
        assertThat(spoken()).isEqualTo("0:10")
    }

    @Test
    fun `at 2x the time left is the listening time, the time played stays the book's`() {
        place = place.copy(speed = 2f)
        show()

        compose.onNodeWithText("0:10").assertIsDisplayed()
        compose.onNodeWithText("−0:15").assertIsDisplayed()
    }

    @Test
    fun `a change of speed changes the time left at once`() {
        show()
        compose.onNodeWithText("−0:30").assertIsDisplayed()

        place = place.copy(speed = 2f)

        compose.onNodeWithText("−0:15").assertIsDisplayed()
        compose.onNodeWithText("0:10").assertIsDisplayed()
    }
}
