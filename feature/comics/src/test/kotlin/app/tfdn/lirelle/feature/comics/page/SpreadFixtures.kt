package app.tfdn.lirelle.feature.comics.page

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.layout.spreads
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.feature.comics.ComicNavigation
import app.tfdn.lirelle.feature.comics.ComicPhase
import app.tfdn.lirelle.feature.comics.ComicReaderUiState
import java.io.File

internal const val AlbumPages = 48

/** Remembers what the page view asked for, in order. */
internal class RecordingNavigation : ComicNavigation {
    val calls = mutableListOf<String>()

    /** The pages asked sharper, each with the long side asked: apart from [calls], which are what the user did. */
    val sharpened = mutableListOf<Pair<Int, Int>>()

    override fun next() {
        calls += "next"
    }

    override fun previous() {
        calls += "previous"
    }

    override fun goTo(pageIndex: Int) {
        calls += "goTo $pageIndex"
    }

    override fun previewPage(pageIndex: Int?) {
        calls += "preview $pageIndex"
    }

    override fun retryPage(index: Int) {
        calls += "retry $index"
    }

    override fun sharpenPage(index: Int, longSidePx: Int) {
        sharpened += index to longSidePx
    }
}

/**
 * An open album of 48 pages, on the spread [spreadIndex]. With [twoPages] its spreads are the cover, then the
 * pages 1 and 2, 3 and 4, and so on (indexes from 0).
 */
internal fun album(
    spreadIndex: Int = 0,
    twoPages: Boolean = false,
    settings: ComicSettings = ComicSettings(),
    forward: Boolean = true,
    pages: Map<Int, PageState> = emptyMap(),
) = ComicReaderUiState(
    phase = ComicPhase.Ready,
    pageCount = AlbumPages,
    twoPages = twoPages,
    spreads = spreads(AlbumPages, wide = emptySet(), twoPages = twoPages),
    spreadIndex = spreadIndex,
    forward = forward,
    settings = settings,
    pages = pages,
)

/**
 * A page on the device: a real image of [width] by [height] in [folder], all of the [colour] given (an Android
 * colour), black without one. Call it from the test's thread: the graphics of Robolectric must first be loaded
 * there.
 */
internal fun pageImage(folder: File, index: Int, width: Int, height: Int, colour: Int? = null): PageState.Ready {
    val file = File(folder, "$index-${width}x$height")
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    colour?.let(bitmap::eraseColor)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    return PageState.Ready(file, width, height)
}

/** Where the node is drawn in the window, the zoom and the place of the spread counted, nothing cut away. */
internal fun SemanticsNodeInteraction.drawn(): Rect {
    val coordinates = fetchSemanticsNode().layoutInfo.coordinates
    val size = coordinates.size
    return Rect(
        coordinates.localToRoot(Offset.Zero),
        coordinates.localToRoot(Offset(size.width.toFloat(), size.height.toFloat())),
    )
}

/** The page view's place in the window. */
internal fun ComposeTestRule.viewport(): Rect = onNodeWithTag(ComicSpreadTag).drawn()

/** A tap at [x] of the width of the page view (0 its left, 1 its right), at mid-height, and what it started. */
internal fun ComposeTestRule.tapAt(x: Float) {
    onNodeWithTag(ComicSpreadTag).performTouchInput { click(Offset(width * x, height / 2f)) }
    waitForIdle()
}

/** A finger that comes down [at] a place of the page view and stays there until it is a long press. */
internal fun ComposeTestRule.pressLong(at: Offset) {
    var timeout = 0L
    onNodeWithTag(ComicSpreadTag).performTouchInput {
        timeout = viewConfiguration.longPressTimeoutMillis
        down(at)
    }
    mainClock.advanceTimeBy(timeout + LongPressMargin)
    waitForIdle()
}

/** A long press [at] a place of the page view, the finger lifted after it. */
internal fun ComposeTestRule.longPressAt(at: Offset) {
    pressLong(at)
    onNodeWithTag(ComicSpreadTag).performTouchInput { up() }
    waitForIdle()
}

/** Counts the ticks the page view gives under the finger. */
internal class RecordingHaptics : HapticFeedback {
    val ticks = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        ticks += hapticFeedbackType
    }
}

/** Long enough for a slide, or the walk of a page, to end. */
internal const val SettleWait = 1_000L

/** How long past the long-press timeout a finger of these tests stays down. */
internal const val LongPressMargin = 50L
