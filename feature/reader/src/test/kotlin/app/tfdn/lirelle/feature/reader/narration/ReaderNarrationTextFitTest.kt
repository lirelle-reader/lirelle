package app.tfdn.lirelle.feature.reader.narration

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.reader.DeepToc
import app.tfdn.lirelle.feature.reader.Location
import app.tfdn.lirelle.feature.reader.LongChapter
import app.tfdn.lirelle.feature.reader.LongTitle
import app.tfdn.lirelle.feature.reader.ReaderContent
import app.tfdn.lirelle.feature.reader.ReaderPhase
import app.tfdn.lirelle.feature.reader.ReaderTopBarTag
import app.tfdn.lirelle.feature.reader.ReaderUiState
import app.tfdn.lirelle.feature.reader.RecordingActions
import app.tfdn.lirelle.feature.reader.SelectionUi
import app.tfdn.lirelle.feature.reader.bridge.PageBounds
import app.tfdn.lirelle.feature.reader.web.testBook
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode
import app.tfdn.lirelle.feature.player.R as PlayerR

private const val LongSection = "Twelve. The crossing of the grey sea, at dawn, with the notebook of her grandfather"

/** No text of the reader during a narration overflows or is clipped, at any width, language or text size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ReaderNarrationTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val folder = TemporaryFolder()

    private val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)

    private val narrated = ReaderNarrationUi(
        available = true,
        active = narrationUi().copy(sectionTitle = LongSection, elapsedMs = 9 * 3_600_000L, speed = 2.75f),
    )

    /** A narrated book on screen with a long title, a long chapter name and a long section title. */
    private val reading by lazy {
        ReaderUiState(
            title = LongTitle,
            phase = ReaderPhase.Reading(testBook(folder.newFile("book.epub"))),
            opened = true,
            toc = DeepToc,
            location = Location(LongChapter, tocId = 4, fraction = 0.999, page = 2, pages = 123, atEnd = false),
            settings = ReaderSettings(header = nothing, footer = nothing),
            narration = narrated,
        )
    }

    /**
     * The texts in a slot of one line: they end with an ellipsis instead of wrapping. The ended bar's second line
     * is meant to be cut too.
     */
    private val oneLine by lazy {
        setOf(LongTitle, LongChapter, LongSection, compose.activity.getString(PlayerR.string.narration_finished_rest))
    }

    @Test
    fun `the bar with the strip's two ways back`() {
        show(reading.copy(narration = narrated.copy(strip = StripUi.BackAndReadPage)))
    }

    @Test
    fun `the bar with the controls, the wave button tinted, and the strip's way back`() {
        show(reading.copy(controlsShown = true, narration = narrated.copy(strip = StripUi.BackOnly)))

        val topBar = compose.onNodeWithTag(ReaderTopBarTag).getUnclippedBoundsInRoot()
        val wave = compose.onNodeWithTag(WaveButtonTag).getUnclippedBoundsInRoot()
        assertWithMessage("wave in the top bar").that(wave.right).isAtMost(topBar.right)
        assertWithMessage("wave is whole").that((wave.right - wave.left).value).isAtLeast(47.5f)
        val buttons = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(ReaderTopBarTag)))
            .fetchSemanticsNodes()
        assertWithMessage("buttons of the top bar").that(buttons).hasSize(TopBarButtons)
        val bar = compose.onNodeWithTag(ReaderTopBarTag).fetchSemanticsNode().boundsInRoot
        val least = with(compose.density) { 48.dp.toPx() } - 1f
        buttons.forEach { button ->
            val bounds = button.boundsInRoot
            val name = button.config.getOrNull(SemanticsProperties.ContentDescription)
            assertWithMessage("$name inside the bar").that(bounds.left).isAtLeast(bar.left - 1f)
            assertWithMessage("$name inside the bar").that(bounds.right).isAtMost(bar.right + 1f)
            assertWithMessage("$name is whole").that(bounds.width).isAtLeast(least)
        }
        // Left out of a bar too narrow for it, the title is still heard.
        compose.onNode(hasContentDescription(LongTitle)).assertExists()
    }

    @Test
    fun `the ended bar with the controls`() {
        val ended = narrated.copy(active = narrationUi(playing = false, finished = true), ended = true)
        show(reading.copy(controlsShown = true, narration = ended))
    }

    @Test
    fun `a skipped section's notice over the bar`() {
        show(reading.copy(narration = narrated.copy(notice = SkippedNotice(NarratedBook, 1, LongSection))))
    }

    @Test
    fun `the selection's card headed by read from here`() {
        val selection = SelectionUi("epubcfi(/6/4!/4/2,/1:0,/1:4)", "Some", 0, PageBounds(0.2, 0.5, 0.6, 0.55), null)
        show(reading.copy(selection = selection, narration = ReaderNarrationUi(available = true)))
    }

    @Test
    fun `the narration's problems above the bar`() {
        listOf(
            narrationUi().copy(offline = true),
            narrationUi().copy(unreachable = true),
            narrationUi(playing = false).copy(error = true),
            narrationUi(playing = false).copy(sectionCount = 0, loadFailed = true),
        ).forEach { active -> show(reading.copy(narration = narrated.copy(active = active))) }
        show(reading.copy(narration = ReaderNarrationUi(available = true, loadFailed = true)))
    }

    @Test
    fun `narration unavailable`() {
        show(reading.copy(narration = ReaderNarrationUi(available = true, unavailable = true)))
    }

    private var shown by mutableStateOf(ReaderUiState())

    private var composed = false

    private fun show(state: ReaderUiState): List<LaidOutText> {
        shown = state
        if (composed) return compose.assertAllTextFits(oneLine)
        composed = true
        val width = if (screen == Wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    ReaderContent(
                        state = shown,
                        actions = RecordingActions(),
                        engineSlot = { Box(it.testTag(PageTag)) },
                        narrationControls = RecordingNarrationActions(),
                    )
                }
            }
        }
        return compose.assertAllTextFits(oneLine)
    }

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val Phone = "w411dp-h891dp"
        private const val Wide = "w1280dp-h800dp"
        private const val PageTag = "page"

        /** Back, the wave, search, the bookmark, the panel and the settings. */
        private const val TopBarButtons = 6

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
