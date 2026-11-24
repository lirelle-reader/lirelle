package app.tfdn.lirelle.feature.comics.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.comics.ComicFailure
import app.tfdn.lirelle.feature.comics.ComicPhase
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.ComicSettingsButtonTag
import app.tfdn.lirelle.feature.comics.PanelReading
import app.tfdn.lirelle.feature.comics.SeriesLine
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongTitle = "Quelque part entre les ombres, là où la lumière du matin ne va jamais"
private const val LongSeries = "Les Aventures extraordinaires d’Adèle Blanc-Sec"

/** The least width left to the title, in pixels of a screen that has one per dp. */
private const val MinTitleWidth = 150f

/**
 * No text of the bars, of the offer of a newer page, of an opening that failed or of the settings sheet
 * overflows or is clipped, at any width, language or text size.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ComicChromeTextFitTest(screenSize: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screenSize", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val screen = ScreenUnderTest()

    /** The answer that turns the mode off is long: it may take two lines, whole. */
    private val bannerOff = setOf("Turn panel by panel off", "Désactiver le case par case")

    /** The title and the series line are cut short on their one line, and that only. */
    private val oneLine = setOf(LongTitle, "$LongSeries #12", "$LongSeries nº 12")

    @Test
    fun `the bars, with one page`() {
        screen.state = longAlbum(openAlbum(spreadIndex = 47))

        val texts = show()

        assertThat(texts.filter { it.text in oneLine }).hasSize(2)
        assertThat(texts.filter { it.text in oneLine }.all { it.lineCount == 1 }).isTrue()
        assertThat(texts.count { "48" in it.text }).isEqualTo(1)
        assertThat(texts.count { "100" in it.text }).isEqualTo(1)
    }

    @Test
    fun `the bars, with two pages of an album of hundreds, right to left`() {
        val settings = ComicSettings(direction = Direction.RightToLeft, background = Background.White)
        screen.state = longAlbum(openAlbum(twoPages = true, settings = settings))
            .copy(pageCount = 248, spreads = listOf(Spread(245, 246)), spreadIndex = 0)

        val texts = show()

        assertThat(texts.count { "246-247" in it.text }).isEqualTo(1)
    }

    @Test
    fun `the offer of a newer page, over the bottom bar and alone`() {
        screen.state = longAlbum(openAlbum()).copy(pageCount = 248, newerPage = 247)
        assertThat(show().count { "247" in it.text }).isEqualTo(1)

        screen.state = screen.state.copy(barsShown = false)

        assertThat(show()).hasSize(3)
    }

    @Test
    fun `an opening that failed, for each reason`() {
        val reasons =
            listOf(ApiError.ServerUnreachable, ApiError.NotFound, ApiError.ServerFailed, ApiError.Unexpected)
                .map { ComicFailure.Load(it) } + ComicFailure.NoComic + ComicFailure.Protected +
                ComicFailure.Damaged + ComicFailure.KeptDamaged
        reasons.forEach { reason ->
            screen.state = ComicReaderUiState(phase = ComicPhase.Failed(reason), title = LongTitle)

            assertThat(show().size).isAtLeast(2)
        }
    }

    @Test
    fun `a document being fetched, its size known or not`() {
        val fetches = listOf(
            OpeningProgress(3_200_000, 9_400_000),
            OpeningProgress(1_234_567_890, 2_345_678_901),
            OpeningProgress(3_200_000, null),
        )
        fetches.forEach { fetch ->
            screen.state = ComicReaderUiState(title = LongTitle, kind = PagedKind.Pdf, opening = fetch)

            assertThat(show().size).isAtLeast(2)
        }
    }

    @Test
    fun `the settings sheet, for a series and for an album alone`() {
        screen.state = longAlbum(openAlbum())
        show()
        compose.onNodeWithTag(ComicSettingsButtonTag).performClick()

        assertThat(show().count { LongSeries in it.text }).isEqualTo(2)

        screen.state = screen.state.copy(series = null, directionScope = DirectionScope.Book(BookId("14")))

        assertThat(show().count { LongSeries in it.text }).isEqualTo(0)
    }

    @Test
    fun `the top bar with the button of the panels leaves the title its room`() {
        screen.state = longAlbum(openAlbum()).copy(panels = PanelReading(offered = true, on = true))

        val title = show().single { it.text == LongTitle }

        assertThat(title.lineCount).isEqualTo(1)
        // Three buttons of 48 dp and the bar's margins, in the narrowest window: half of it is the title's.
        assertThat(title.boundsInRoot.width).isAtLeast(MinTitleWidth)
    }

    @Test
    fun `what is told the first time the panels are turned on`() {
        screen.state = longAlbum(openAlbum()).copy(panels = PanelReading(offered = true, notice = true))

        val texts = show()

        assertThat(texts.filter { it.isEllipsized && it.text !in oneLine }).isEmpty()
        assertThat(texts.count { it.text.startsWith("Reading panel") || it.text.startsWith("Lecture case") })
            .isEqualTo(1)
    }

    @Test
    fun `what an album that lends itself badly says, over the bottom bar and alone`() {
        val told = PanelReading(offered = true, on = true, albumBanner = true)
        screen.state = longAlbum(openAlbum()).copy(panels = told)

        val banner = show().filter { it.text in bannerTexts }

        assertThat(banner).hasSize(3)
        assertThat(banner.filter { it.isEllipsized }).isEmpty()

        screen.state = screen.state.copy(barsShown = false)

        assertThat(show()).hasSize(3)
    }

    @Test
    fun `the settings sheet of an album offered the panels`() {
        screen.state = longAlbum(openAlbum()).copy(panels = PanelReading(offered = true))
        show()
        compose.onNodeWithTag(ComicSettingsButtonTag).performClick()

        val texts = show()

        assertThat(texts.count { it.text.startsWith("Whole page first") || it.text.startsWith("Planche entière") })
            .isEqualTo(1)
        assertThat(texts.filter { it.isEllipsized && it.text !in oneLine }).isEmpty()
    }

    private fun longAlbum(state: ComicReaderUiState) =
        state.copy(title = LongTitle, series = SeriesLine(LongSeries, "12"))

    /** The sentence of an album that lends itself badly, and its two answers, in both languages. */
    private val bannerTexts = setOf(
        "Few pages of this album can be cut into panels. They are shown whole.",
        "Peu de planches de cet album se découpent en cases. Elles s’affichent entières.",
        "OK",
    ) + bannerOff

    private var shown = false

    private fun show(): List<LaidOutText> {
        if (!shown) {
            compose.setContent { screen.Content() }
            shown = true
        }
        compose.waitForIdle()
        return compose.assertAllTextFits(unbreakable = oneLine, wrappingLabels = bannerOff)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = deviceCases()
    }
}
