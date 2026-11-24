package app.tfdn.lirelle.feature.book

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.laidOutTexts
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The round button of a reading action: what it writes, says, draws and does. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ActionRingTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var clicks = 0

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun ring(kind: ActionKind, status: RingStatus, enabled: Boolean = true, narrated: Boolean = false) =
        ActionRingUi(kind, enabled, status, ringFraction(status), narrated)

    private fun show(ui: ActionRingUi, beside: Boolean = false) = compose.setContent {
        LirelleTheme {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ActionRing(ui, Tag, beside, onClick = { clicks++ })
            }
        }
    }

    private fun button() = compose.onNodeWithTag(Tag)

    /** The words written on the button, in their order: the name, then the status. */
    private fun words(): List<String> = compose.laidOutTexts().map { it.text }

    private fun assertWrites(kind: ActionKind, status: RingStatus, name: Int, written: String?) {
        show(ring(kind, status))

        val expected = listOf(text(name)) + written?.split(' ').orEmpty()
        assertWithMessage("the words of $kind, $status").that(words()).isEqualTo(expected)
    }

    @Test
    fun `Read never started says Start`() =
        assertWrites(ActionKind.Read, RingStatus.Start, R.string.action_read, "Start")

    @Test
    fun `Read started says Resume and the percentage`() =
        assertWrites(ActionKind.Read, RingStatus.ResumeAt(62), R.string.action_read, "Resume\u00A0· 62%")

    @Test
    fun `Read started with no percentage says Resume`() =
        assertWrites(ActionKind.Read, RingStatus.Resume, R.string.action_read, "Resume")

    @Test
    fun `Read finished says Read again, finished`() =
        assertWrites(ActionKind.Read, RingStatus.Again, R.string.action_read, "Read again\u00A0· finished")

    @Test
    fun `Listen finished says Listen again, finished`() =
        assertWrites(ActionKind.Listen, RingStatus.Again, R.string.action_listen, "Listen again\u00A0· finished")

    @Test
    fun `Listen started says Resume and the percentage`() =
        assertWrites(ActionKind.Listen, RingStatus.ResumeAt(12), R.string.action_listen, "Resume\u00A0· 12%")

    @Test
    fun `Listen with no status writes its name alone`() =
        assertWrites(ActionKind.Listen, RingStatus.None, R.string.action_listen, null)

    @Test
    fun `a format not on the device says Offline`() =
        assertWrites(ActionKind.Listen, RingStatus.Offline, R.string.action_listen, "Offline")

    @Test
    fun `a format not on the device of a book gone says Unavailable`() =
        assertWrites(ActionKind.Read, RingStatus.Unavailable, R.string.action_read, "Unavailable")

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun `in French, the dot stays with the word before it and the percentage keeps its sign`() {
        show(ring(ActionKind.Read, RingStatus.ResumeAt(62)))

        assertThat(words()).containsExactly("Lire", "Reprendre\u00A0·", "62\u00A0%").inOrder()
    }

    @Test
    fun `the button is a button, and a tap reaches the action`() {
        show(ring(ActionKind.Read, RingStatus.Start))

        button()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()

        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `a switched-off button is disabled, and a tap does nothing`() {
        show(ring(ActionKind.Listen, RingStatus.Offline, enabled = false))

        button().assertIsNotEnabled().performClick()

        assertThat(clicks).isEqualTo(0)
    }

    @Test
    fun `a screen reader hears the name and the status`() {
        show(ring(ActionKind.Read, RingStatus.ResumeAt(62)))

        button().assertContentDescriptionEquals("Read, resume at 62%")
    }

    @Test
    fun `a screen reader hears that the reader can narrate the book`() {
        show(ring(ActionKind.Read, RingStatus.ResumeAt(62), narrated = true))

        button().assertContentDescriptionEquals("Read, resume at 62%, synchronised narration available")
    }

    @Test
    fun `a screen reader hears each status in words, without the dot`() {
        val spoken = mapOf(
            ring(ActionKind.Read, RingStatus.Start) to "Read, start",
            ring(ActionKind.Read, RingStatus.Resume) to "Read, resume",
            ring(ActionKind.Read, RingStatus.Again) to "Read, read again, finished",
            ring(ActionKind.Listen, RingStatus.Again) to "Listen, listen again, finished",
            ring(ActionKind.Listen, RingStatus.None) to "Listen",
            ring(ActionKind.Listen, RingStatus.Offline, enabled = false) to "Listen, offline",
            ring(ActionKind.Read, RingStatus.Unavailable, enabled = false) to "Read, unavailable",
        )

        val heard = mutableMapOf<ActionRingUi, String>()
        compose.setContent { spoken.keys.forEach { heard[it] = it.spoken() } }
        compose.waitForIdle()

        assertThat(heard).isEqualTo(spoken)
    }

    @Test
    fun `the icon of Read has sound waves when the reader can narrate the book`() {
        show(ring(ActionKind.Read, RingStatus.Start, narrated = true))

        compose.onNodeWithTag(ActionRingWavesTag, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the icon of Read is a plain book otherwise`() {
        show(ring(ActionKind.Read, RingStatus.Start))

        compose.onAllNodesWithTag(ActionRingWavesTag, useUnmergedTree = true).fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
    }

    @Test
    fun `the ring says how far it is filled, and whether the button is the filled one`() {
        show(ring(ActionKind.Read, RingStatus.ResumeAt(62)).copy(filled = true))

        button()
            .assert(SemanticsMatcher.expectValue(ActionRingFilledKey, true))
            .assert(SemanticsMatcher.expectValue(ActionRingFractionKey, 0.62f))
    }

    @Test
    fun `an outlined button says it is not the filled one`() {
        show(ring(ActionKind.Listen, RingStatus.Start))

        button().assert(SemanticsMatcher.expectValue(ActionRingFilledKey, false))
    }

    @Test
    fun `the disc is 68 dp across and the button is a touch target of 48 dp at least`() {
        show(ring(ActionKind.Listen, RingStatus.None))

        val disc = compose.onNodeWithTag(ActionRingDiscTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat(disc.width).isEqualTo(68.dp)
        assertThat(disc.height).isEqualTo(68.dp)
        button().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `the texts are under the disc, centred on it`() {
        show(ring(ActionKind.Read, RingStatus.ResumeAt(62)))

        val disc = compose.onNodeWithTag(ActionRingDiscTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val centre = (disc.left + disc.right).value / 2
        compose.laidOutTexts().forEach { text ->
            assertWithMessage("top of $text").that(text.boundsInRoot.top).isAtLeast(disc.bottom.value)
        }
        val name = compose.laidOutTexts().first().boundsInRoot
        assertThat((name.left + name.right) / 2).isWithin(1f).of(centre)
    }

    @Test
    fun `beside, the texts are at the side of the disc`() {
        show(ring(ActionKind.Read, RingStatus.ResumeAt(62)), beside = true)

        val disc = compose.onNodeWithTag(ActionRingDiscTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        compose.laidOutTexts().forEach { text ->
            assertWithMessage("left of $text").that(text.boundsInRoot.left).isAtLeast(disc.right.value)
            assertWithMessage("top of $text").that(text.boundsInRoot.top).isAtLeast(disc.top.value)
            assertWithMessage("bottom of $text").that(text.boundsInRoot.bottom).isAtMost(disc.bottom.value)
        }
    }

    private companion object {
        const val Tag = "ring-under-test"
    }
}
