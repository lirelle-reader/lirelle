package app.tfdn.lirelle.feature.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.marks.MarkId
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.reader.bridge.SearchHit
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * No text of the search's panel or of its bar on the page overflows or is clipped, at any width,
 * language or text size, with long chapter names and long contexts.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class SearchTextFitTest(screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)

    private val reading by lazy {
        ReaderUiState(
            title = "A book",
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            location = location(),
            settings = ReaderSettings(header = nothing, footer = nothing),
        )
    }

    private val longHit = SearchHit(
        cfi = "epubcfi(/6/4!/4/2/1:0)",
        pre = "…the lamps of the harbour came on one by one, and she took out the little ",
        match = "notebook",
        post = " her father had kept through all those years at sea, its pages swollen with salt…",
    )

    private val found = SearchUi(
        query = "notebook",
        searchId = 3,
        running = false,
        fraction = 1.0,
        groups = listOf(
            SearchRow(1, LongChapter, List(3) { longHit }),
            SearchRow(2, null, listOf(longHit.copy(pre = "", post = ""))),
            SearchRow(3, LongChapter, List(2) { longHit }),
        ),
        total = 6,
        truncated = false,
        current = null,
        onPage = false,
    )

    /** As many results as a search can find, the last one shown: the widest counter. */
    private val many = found.copy(
        groups = listOf(SearchRow(1, LongChapter, List(1_000) { longHit })),
        total = 1_000,
        truncated = true,
        current = 999,
        onPage = true,
    )

    @Test
    fun `the panel with long chapters and long contexts`() {
        val texts = show(reading.copy(openSheet = ReaderSheet.Search, search = found))

        assertWithMessage("rows shown").that(texts.count { it.text.contains(longHit.match) }).isAtLeast(1)
        assertNotCut(texts)
    }

    @Test
    fun `the panel while searching`() {
        assertNotCut(show(reading.copy(openSheet = ReaderSheet.Search, search = found.copy(running = true))))
    }

    @Test
    fun `the panel of a search stopped at its limit`() {
        assertNotCut(show(reading.copy(openSheet = ReaderSheet.Search, search = many.copy(onPage = false))))
    }

    @Test
    fun `the panel of a search that found nothing`() {
        val none = found.copy(groups = emptyList(), total = 0)
        assertNotCut(show(reading.copy(openSheet = ReaderSheet.Search, search = none)))
    }

    @Test
    fun `the empty panel, with its hint`() {
        val empty = SearchUi("", null, false, 0.0, emptyList(), 0, false, null, false)
        val texts = show(reading.copy(openSheet = ReaderSheet.Search, search = empty))

        val hint = compose.activity.getString(R.string.reader_search_book)
        assertWithMessage("hint shown").that(texts.map { it.text }).contains(hint)
        assertNotCut(texts)
    }

    @Test
    fun `the bar with the widest counter, go back, the controls and undo`() {
        // The title is in a slot of one line, and gives way to the top bar's buttons: it may end with an ellipsis.
        val texts = show(
            reading.copy(
                search = many,
                goBackShown = true,
                controlsShown = true,
                undo = UndoUi.Highlight(MarkId("h1")),
            ),
            unbreakable = setOf(reading.title),
        )

        val counter = compose.activity.getString(R.string.reader_search_counter_more, 1_000, 1_000)
        assertWithMessage("counter shown").that(texts.map { it.text }).contains(counter)
        compose.onNodeWithContentDescription(goBack).assertIsDisplayed()
        assertNotCut(texts.filter { it.text != reading.title })
    }

    @Test
    fun `the bar with the widest counter and go back, on the page alone`() {
        val texts = show(reading.copy(search = many, goBackShown = true))

        compose.onNodeWithContentDescription(goBack).assertIsDisplayed()
        assertNotCut(texts)
    }

    private val goBack by lazy { compose.activity.getString(R.string.reader_go_back) }

    @Test
    fun `the bar before a result is shown`() {
        assertNotCut(show(reading.copy(search = many.copy(current = null))))
    }

    private fun assertNotCut(texts: List<LaidOutText>) {
        texts.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isFalse() }
    }

    private fun show(state: ReaderUiState, unbreakable: Set<String> = emptySet()): List<LaidOutText> {
        compose.setContent {
            LirelleTheme {
                ReaderContent(state = state, actions = RecordingActions(), engineSlot = { Box(it) })
            }
        }
        return compose.assertAllTextFits(unbreakable)
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
