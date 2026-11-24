package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.ui.BookCover
import app.tfdn.lirelle.feature.player.bookmarks.ChaptersAndBookmarks
import app.tfdn.lirelle.feature.player.bookmarks.PlayerBookmarks
import app.tfdn.lirelle.feature.player.controller.PlayerUiState

internal const val ChaptersColumnTag = "player-chapters-column"
internal const val PlayerCoverTag = "player-cover"

/** From this size, in landscape, the chapters may be a column always shown beside the player. */
private val TwoPaneMinWidth = 840.dp
private val TwoPaneMinHeight = 480.dp

/**
 * The width the five controls take, with the buffering ring around the big one and a little room
 * between them: a column beside the cover is never narrower.
 */
private val ControlsWidth = 304.dp

private val PhoneCoverMax = 264.dp
private val LandscapeCoverMax = 320.dp
private val CoverMin = 96.dp

/** Two panes only with a cover at least this large; a smaller window has the chapters in their sheet. */
private val TwoPaneCoverMin = 200.dp

/** From this cover size, "Go back" over it has its label; under it, an icon that says it. */
private val LabelledGoBackMin = 160.dp
private val CoverGoBackMargin = 12.dp
private val SectionGap = 16.dp

/** At most this share of the height goes to the cover: the controls stay in view. */
private const val PhoneCoverShare = 0.4f

/** From this width, an upright window is large: its cover grows, and the player is centred. */
private val LargePortraitMinWidth = 600.dp

/** The player of a large upright window is no wider than this column. */
private val LargePortraitColumnMax = 600.dp
private val PhoneSide = 24.dp
private val TabletSide = 48.dp
private val CoverGap = 32.dp
private val PaneGap = 40.dp
private val LandscapeTop = 8.dp
private val LandscapeBottom = 8.dp
private val ChaptersColumnWidth = 380.dp

/** The bottom row does not stretch over a wide pane. */
private val WideBottomRowMax = 360.dp

/** How the player lays itself out in the room it has. */
internal sealed interface PlayerLayout {
    /**
     * The cover above the rest, which scrolls when taller than the window, [side] from the edges; on a
     * [large] window, the player no wider than a column and centred in the room above the bottom row.
     */
    data class Portrait(val cover: Dp, val side: Dp, val large: Boolean) : PlayerLayout

    /**
     * The cover beside the rest, [side] from the edges; with [chapters], the chapters always shown in
     * a column on the right.
     */
    data class Landscape(val cover: Dp, val side: Dp, val chapters: Boolean) : PlayerLayout
}

/**
 * The layout for [width] and [height]: two panes only for a window both wide and high enough for the
 * column of controls beside the cover and the chapters; the cover beside the controls on any other
 * window wider than high where they fit; the cover above them otherwise.
 */
internal fun playerLayout(width: Dp, height: Dp): PlayerLayout {
    if (width > height) {
        if (width >= TwoPaneMinWidth && height >= TwoPaneMinHeight) {
            landscapeCover(width - ChaptersColumnWidth - PaneGap, height, TabletSide)
                ?.takeIf { it >= TwoPaneCoverMin }
                ?.let { return PlayerLayout.Landscape(it, TabletSide, chapters = true) }
        }
        landscapeCover(width, height, PhoneSide)?.let { return PlayerLayout.Landscape(it, PhoneSide, chapters = false) }
    }
    val large = width >= LargePortraitMinWidth
    val side = if (large) TabletSide else PhoneSide
    val content = minOf(width - side * 2, LargePortraitColumnMax)
    // A phone's cover keeps its size; a large window's grows with it, as wide as the column at most.
    val cover = minOf(content, if (large) content else PhoneCoverMax, height * PhoneCoverShare)
    return PlayerLayout.Portrait(cover.coerceAtLeast(CoverMin), side, large)
}

/** The cover beside a column as wide as the controls at least, in [width]; null when they do not fit. */
private fun landscapeCover(width: Dp, height: Dp, side: Dp): Dp? {
    val room = width - side * 2 - CoverGap - ControlsWidth
    val cover = minOf(LandscapeCoverMax, height - LandscapeTop - LandscapeBottom, room)
    return cover.takeIf { it >= CoverMin }
}

/**
 * The cover, the texts, the bar, the controls, and the bottom row at the bottom of the window. The
 * controls and the bottom row always keep their room; the cover shrinks, down to nothing, so that
 * the texts above the controls stay in view on a low window, and what does not fit scrolls.
 */
@Composable
internal fun PortraitPlayer(
    state: PlayerUiState,
    actions: PlayerActions,
    onSheet: (PlayerSheet) -> Unit,
    layout: PlayerLayout.Portrait,
    bookmarks: PlayerBookmarks?,
) {
    PinnedColumn(
        gap = SectionGap,
        centreAbove = layout.large,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = layout.side, vertical = 8.dp)
            .wrapContentWidth()
            .widthIn(max = LargePortraitColumnMax),
        top = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val room = maxHeight
                var rest by remember { mutableStateOf<Dp?>(null) }
                val density = LocalDensity.current
                val cover = rest?.let { minOf(layout.cover, room - it - SectionGap) } ?: layout.cover
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SectionGap),
                ) {
                    if (cover >= CoverMin) {
                        Cover(state.coverUrl, state.title, cover, Modifier.align(Alignment.CenterHorizontally))
                    }
                    Column(
                        modifier = Modifier.onSizeChanged { rest = with(density) { it.height.toDp() } },
                        verticalArrangement = Arrangement.spacedBy(SectionGap),
                    ) {
                        NowPlayingTop(state, actions, centred = true, goBackHere = true)
                    }
                }
            }
        },
        controls = { Controls(state, actions) },
        bottom = { PlayerBottomRow(state, onSheet, withChapters = true, bookmarks, Modifier) },
    )
}

/**
 * The cover on the left, with "Go back" over it, and the player beside it, its controls and bottom
 * row always in view; with [PlayerLayout.Landscape.chapters], the chapters always shown on the right,
 * where a tap on one goes to it with "Go back".
 */
@Composable
internal fun LandscapePlayer(
    state: PlayerUiState,
    actions: PlayerActions,
    onSheet: (PlayerSheet) -> Unit,
    layout: PlayerLayout.Landscape,
    bookmarks: PlayerBookmarks?,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = layout.side, end = layout.side, top = LandscapeTop, bottom = LandscapeBottom),
        horizontalArrangement = Arrangement.spacedBy(PaneGap),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(CoverGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Go back" over the cover, no wider than it: the column beside keeps its width, nothing moves.
            Box(Modifier.size(layout.cover), contentAlignment = Alignment.BottomCenter) {
                Cover(state.coverUrl, state.title, layout.cover)
                if (state.goBackShown) CoverGoBack(layout.cover, actions::goBack)
            }
            PinnedColumn(
                gap = 12.dp,
                stacked = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                top = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NowPlayingTop(state, actions, centred = false, goBackHere = false)
                    }
                },
                controls = { Controls(state, actions) },
                bottom = {
                    val row = Modifier.widthIn(max = WideBottomRowMax)
                    PlayerBottomRow(state, onSheet, withChapters = !layout.chapters, bookmarks, row)
                },
            )
        }
        if (layout.chapters) ChaptersColumn(state, actions::goToChapter, bookmarks)
    }
}

/** "Go back" over a cover of [cover]: labelled when the cover is wide enough, an icon otherwise. */
@Composable
private fun CoverGoBack(cover: Dp, onGoBack: () -> Unit) {
    val place = Modifier.padding(CoverGoBackMargin)
    if (cover >= LabelledGoBackMin) {
        GoBackButton(onGoBack, place.widthIn(max = cover - CoverGoBackMargin * 2))
    } else {
        GoBackIconButton(onGoBack, place)
    }
}

/** The chapters (or the parts) always shown, and the bookmarks, when there are any to have, in a tab beside them. */
@Composable
private fun ChaptersColumn(state: PlayerUiState, onChoose: (Int) -> Unit, bookmarks: PlayerBookmarks?) {
    Surface(
        modifier = Modifier
            .width(ChaptersColumnWidth)
            .fillMaxHeight()
            .testTag(ChaptersColumnTag),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        val chapters: @Composable ColumnScope.() -> Unit = {
            ChaptersHeader(state.chapters.size, state.totalMs, Modifier.padding(top = 14.dp), state.chapterNames)
            ChapterList(
                bookId = state.bookId,
                chapters = rememberedChapters(state),
                chapterIndex = state.chapterIndex,
                currentLeftMs = currentLeftMs(state),
                onChoose = onChoose,
                modifier = Modifier.weight(1f),
            )
        }
        if (bookmarks == null) {
            Column(Modifier.padding(top = 6.dp), content = chapters)
        } else {
            ChaptersAndBookmarks(state, bookmarks, onGone = {}, Modifier.padding(top = 6.dp), chapters)
        }
    }
}

@Composable
private fun Cover(url: String?, title: String, size: Dp, modifier: Modifier = Modifier) {
    // The title is beside it: a book without a cover shows a plain tile.
    BookCover(
        url = url,
        title = title,
        shape = CoverShape.Square,
        modifier = modifier
            .size(size)
            .testTag(PlayerCoverTag),
        showTitleWhenMissing = false,
    )
}

/** The texts, the bar, the book's line and "Go back", each given only what it shows. */
@Composable
private fun NowPlayingTop(state: PlayerUiState, actions: PlayerActions, centred: Boolean, goBackHere: Boolean) {
    NowPlayingTexts(state.title, creditsText(state.credits), state.chapterTitle)
    PlayingDetails(
        bar = BarPlace(state.chapterIndex, state.chapterElapsedMs, state.chapterDurationMs, state.speed),
        line = if (state.finished != null) null else bookLineText(state),
        finished = state.finished != null,
        goBackShown = state.goBackShown,
        goBackHere = goBackHere,
        actions = actions,
        centred = centred,
    )
}

/** The five controls, given only what they show. */
@Composable
private fun Controls(state: PlayerUiState, actions: PlayerActions) {
    val transport = Transport(
        playing = state.playing,
        buffering = state.buffering,
        finished = state.finished != null,
        backSeconds = state.settings.backSeconds,
        forwardSeconds = state.settings.forwardSeconds,
        ratings = state.capabilities.ratings,
    )
    TransportControls(transport, actions, onRate = actions::openEndPanel)
}

/**
 * The tiles; without [withChapters], the chapters are always shown, and a bookmark already there is shown there.
 * No bookmark tile without [bookmarks].
 */
@Composable
private fun PlayerBottomRow(
    state: PlayerUiState,
    onSheet: (PlayerSheet) -> Unit,
    withChapters: Boolean,
    bookmarks: PlayerBookmarks?,
    modifier: Modifier,
) {
    BottomRow(
        speed = state.speed,
        sleepLeft = sleepLeftText(state),
        sleeping = state.sleep != null,
        onSpeed = { onSheet(PlayerSheet.Speed) },
        onSleep = { onSheet(PlayerSheet.Sleep) },
        onChapters = if (withChapters) ({ onSheet(PlayerSheet.Chapters) }) else null,
        modifier = modifier,
        chaptersName = state.chapterNames.title,
        onBookmark = bookmarks?.let { { it.add(inSheet = withChapters) } },
    )
}
