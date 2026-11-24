package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.HighlightColour
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.laidOutTexts
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

internal const val LongPassage = "It was the kind of evening on which the lamps of the harbour came on one by one, " +
    "slowly, as if each of them had to be persuaded, and the boats rocked against the quay with a patience " +
    "that seemed older than the town itself, older than anyone who had ever lived in it or left it."

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightPanelTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val recording = RecordingActions()

    private var panel by mutableStateOf(
        HighlightPanelUi(
            id = MarkId("h1"),
            text = "a short passage",
            color = HighlightColour.Blue.hex,
            note = "",
            draft = "",
            focusNote = false,
            confirmDiscard = false,
        ),
    )

    /** The notes typed, as the panel hands them over. */
    private val drafts = mutableListOf<String>()

    /** Whether the reader gives the typed note back, as the view model does a frame later. */
    private var echo = true

    /** The reader under the panel: a typed note is held as the view model holds it. */
    private val actions = object : ReaderActions by recording {
        override fun editNote(draft: String) {
            drafts += draft
            if (echo) panel = panel.copy(draft = draft)
        }
    }

    private fun string(@StringRes id: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun show() {
        compose.setContent {
            LirelleTheme {
                ReaderChromeTheme(ReaderTheme.Sepia.colours(systemDark = false)) {
                    HighlightPanel(panel, actions)
                }
            }
        }
    }

    private fun field() = compose.onNodeWithTag(NoteFieldTag)

    /** What the note field holds, without its count. */
    private fun typed() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun save() = compose.onNodeWithText(string(R.string.reader_highlight_save))

    @Test
    fun `the page stays visible above the panel`() {
        show()

        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val bounds = compose.onNodeWithTag(HighlightPanelTag).getUnclippedBoundsInRoot()
        assertThat(bounds.bottom).isEqualTo(screen.bottom)
        assertThat(bounds.height.value).isAtMost(screen.height.value * MaxSheetFraction + 1f)
    }

    @Test
    fun `a tap on the page above closes the panel, a tap on the panel does not`() {
        show()

        compose.onNodeWithText("a short passage").performClick()
        assertThat(recording.calls).isEmpty()

        compose.onRoot().performTouchInput { click(topCenter.copy(y = 40f)) }
        assertThat(recording.calls).containsExactly("closeHighlightPanel")
    }

    @Test
    fun `the highlighted text takes three lines at most`() {
        panel = panel.copy(text = LongPassage)
        show()

        val quote = compose.laidOutTexts().single { it.text == LongPassage }
        assertThat(quote.lineCount).isEqualTo(3)
        assertThat(quote.isEllipsized).isTrue()
    }

    @Test
    fun `the current colour is marked, and a tap changes it at once`() {
        show()

        compose.onNodeWithContentDescription(string(R.string.reader_highlight_blue)).assertIsSelected()
        compose.onNodeWithContentDescription(string(R.string.reader_highlight_pink)).assertIsNotSelected()
        compose.onNodeWithContentDescription(string(R.string.reader_highlight_pink)).performClick()

        assertThat(recording.calls).containsExactly("setHighlightColour(#F472B6)")
    }

    @Test
    fun `a colour of elsewhere marks none of the dots`() {
        panel = panel.copy(color = "#123456")
        show()

        compose.onNodeWithContentDescription(string(R.string.reader_highlight_yellow)).assertIsNotSelected()
        compose.onNodeWithContentDescription(string(R.string.reader_highlight_blue)).assertIsNotSelected()
    }

    @Test
    fun `the note field shows the draft and hands over what is typed`() {
        panel = panel.copy(note = "Kept", draft = "Kept, and more")
        show()

        field().assertTextContains("Kept, and more")
        field().performTextReplacement("Something else")
        compose.waitForIdle()

        assertThat(drafts).containsExactly("Something else")
    }

    @Test
    fun `an edit past 4 000 characters is refused whole`() {
        show()
        field().performTextReplacement("a".repeat(4_000))
        compose.waitForIdle()
        assertThat(drafts.last()).hasLength(4_000)

        field().performTextReplacement("a".repeat(3_999) + "\uD83D\uDE00")
        compose.waitForIdle()

        assertThat(drafts).hasSize(1)
        assertThat(typed()).isEqualTo("a".repeat(4_000))
    }

    @Test
    fun `what is typed is the field's own, whatever the reader gives back and when`() {
        echo = false
        panel = panel.copy(note = "Kept", draft = "Kept")
        show()

        field().performTextReplacement("Écrit à la main")
        compose.waitForIdle()
        panel = panel.copy(color = HighlightColour.Pink.hex)
        compose.waitForIdle()

        assertThat(typed()).isEqualTo("Écrit à la main")
        assertThat(drafts).containsExactly("Écrit à la main")
    }

    @Test
    fun `the panel is announced as a pane, and its dots are a group of choices`() {
        show()

        val title = compose.onNodeWithTag(HighlightPanelTag).fetchSemanticsNode().config[SemanticsProperties.PaneTitle]
        assertThat(title).isEqualTo(string(R.string.reader_highlight_pane))
        compose.onNodeWithContentDescription(string(R.string.reader_highlight_blue))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
    }

    @Test
    fun `the count shows near the limit only`() {
        panel = panel.copy(draft = "a".repeat(100))
        show()
        compose.onNodeWithText("100 / 4,000").assertDoesNotExist()

        field().performTextReplacement("a".repeat(3_900))
        compose.onNodeWithText("3,900 / 4,000").assertExists()
    }

    @Test
    fun `save is enabled only when the note was changed, and saves it`() {
        panel = panel.copy(note = "Kept", draft = "Kept")
        show()
        save().performScrollTo().assertIsNotEnabled()

        field().performTextReplacement("Kept, and more")
        save().performScrollTo().assertIsEnabled().performClick()

        assertThat(recording.calls).containsExactly("saveNote")
    }

    @Test
    fun `save hides the keyboard`() {
        val keyboard = RecordingKeyboard()
        panel = panel.copy(note = "Kept", draft = "Kept", focusNote = true)
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                LirelleTheme {
                    ReaderChromeTheme(ReaderTheme.Sepia.colours(systemDark = false)) {
                        HighlightPanel(panel, actions)
                    }
                }
            }
        }

        field().performTextReplacement("Kept, and more")
        keyboard.hidden = 0
        save().performScrollTo().performClick()

        assertThat(keyboard.hidden).isEqualTo(1)
        field().assertIsNotFocused()
        assertThat(recording.calls).containsExactly("saveNote")
    }

    @Test
    fun `copy and delete`() {
        show()

        compose.onNodeWithText(string(R.string.reader_highlight_copy)).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.reader_highlight_delete)).performScrollTo().performClick()

        assertThat(recording.calls).containsExactly("selectionAction(Copy)", "deleteHighlight").inOrder()
    }

    @Test
    fun `opened from note, the note field has the focus`() {
        panel = panel.copy(focusNote = true)
        show()

        field().assertIsFocused()
    }

    @Test
    fun `opened from a tap on the highlight, the note field waits`() {
        show()

        field().assertIsNotFocused()
    }

    @Test
    fun `closing with a changed note asks whether to discard it`() {
        show()
        compose.onNodeWithText(string(R.string.reader_note_discard_title)).assertDoesNotExist()

        panel = panel.copy(draft = "Typed", confirmDiscard = true)
        compose.onNodeWithText(string(R.string.reader_note_discard_title)).assertExists()
        compose.onNodeWithText(string(R.string.reader_note_keep_editing)).performClick()
        compose.onNodeWithText(string(R.string.reader_note_discard)).performClick()

        assertThat(recording.calls).containsExactly("keepEditing", "discardNote").inOrder()
    }
}

/** A keyboard that counts the times it was asked to hide. */
private class RecordingKeyboard : SoftwareKeyboardController {
    var hidden = 0

    override fun show() = Unit

    override fun hide() {
        hidden++
    }
}
