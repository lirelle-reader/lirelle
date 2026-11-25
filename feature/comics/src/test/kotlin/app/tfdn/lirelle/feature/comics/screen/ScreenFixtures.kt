package app.tfdn.lirelle.feature.comics.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.feature.comics.ComicEndActions
import app.tfdn.lirelle.feature.comics.ComicNavigation
import app.tfdn.lirelle.feature.comics.ComicReaderActions
import app.tfdn.lirelle.feature.comics.ComicReaderContent
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import app.tfdn.lirelle.feature.comics.SeriesLine
import app.tfdn.lirelle.feature.comics.page.RecordingNavigation
import app.tfdn.lirelle.feature.comics.page.album

internal const val Narrow = "w320dp-h640dp"
internal const val Phone = "w411dp-h891dp"
internal const val Wide = "w1280dp-h800dp"

internal const val Title = "Quelque part entre les ombres"
internal const val SeriesName = "Blacksad"

/** Every width, language and text size the texts are tested at. */
internal fun deviceCases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
    listOf("en", "fr").flatMap { language ->
        listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
    }
}

/** Remembers what the end of the album was asked, in order, among the [calls] of the screen. */
internal class RecordingEnd(private val calls: MutableList<String>) : ComicEndActions {
    override fun setRating(stars: Int?) {
        calls += "rate $stars"
    }

    override fun readNext() {
        calls += "readNext"
    }

    override fun closeAlbum() {
        calls += "closeAlbum"
    }

    override fun retryRating() {
        calls += "retryRating"
    }

    override fun dismissEndPanel() {
        calls += "dismissEndPanel"
    }

    override fun deleteFromDevice() {
        calls += "deleteFromDevice"
    }

    override fun keepOnDevice() {
        calls += "keepOnDevice"
    }
}

/** Remembers what the screen asked for, in order. */
internal class RecordingActions(private val navigation: RecordingNavigation = RecordingNavigation()) :
    ComicReaderActions,
    ComicNavigation by navigation,
    ComicEndActions by RecordingEnd(navigation.calls) {
    val calls: MutableList<String> get() = navigation.calls

    override fun setFit(fit: Fit) {
        calls += "fit $fit"
    }

    override fun setTwoPages(twoPages: TwoPages) {
        calls += "twoPages $twoPages"
    }

    override fun setBackground(background: Background) {
        calls += "background $background"
    }

    override fun setWholePageFirst(first: Boolean) {
        calls += "wholePageFirst $first"
    }

    override fun setDirection(direction: Direction) {
        calls += "direction $direction"
    }

    override fun setPanelMode(on: Boolean) {
        calls += "panels $on"
    }

    override fun closePanelNotice() {
        calls += "closeNotice"
    }

    override fun closeAlbumBanner() {
        calls += "closeBanner"
    }

    override fun showZones(shown: Boolean) {
        calls += "zones $shown"
    }

    override fun stripShows(first: Int, last: Int) {
        calls += "stripShows $first $last"
    }

    override fun stripRested() {
        calls += "stripRested"
    }

    override fun stripPastEnd() {
        calls += "stripPastEnd"
    }

    override fun acceptNewer() {
        calls += "acceptNewer"
    }

    override fun ignoreNewer() {
        calls += "ignoreNewer"
    }

    override fun toggleBars() {
        calls += "toggleBars"
    }

    override fun hideBars() {
        calls += "hideBars"
    }

    override fun retryOpening() {
        calls += "retryOpening"
    }

    override fun leave() {
        calls += "leave"
    }
}

/** The first album of a series, open with its bars shown, on the spread [spreadIndex]. */
internal fun openAlbum(
    spreadIndex: Int = 11,
    twoPages: Boolean = false,
    settings: ComicSettings = ComicSettings(),
    barsShown: Boolean = true,
) = album(spreadIndex = spreadIndex, twoPages = twoPages, settings = settings).copy(
    title = Title,
    series = SeriesLine(SeriesName, "1"),
    directionScope = DirectionScope.Series(SeriesId("3")),
    barsShown = barsShown,
)

/** The screen under test, whose state the test changes; in a build that can be debugged with [debuggable]. */
internal class ScreenUnderTest(initial: ComicReaderUiState = openAlbum(), private val debuggable: Boolean = false) {
    var state by mutableStateOf(initial)
    val actions = RecordingActions()

    @Composable
    fun Content() {
        ComicReaderContent(state, actions, debuggable = debuggable)
    }
}
