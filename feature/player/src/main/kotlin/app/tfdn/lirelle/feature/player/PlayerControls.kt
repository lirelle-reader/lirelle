package app.tfdn.lirelle.feature.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.player.bookmarks.BookmarkTile
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import app.tfdn.lirelle.feature.player.controller.timeLeft

internal const val SpeedButtonTag = "player-speed"
internal const val SleepButtonTag = "player-sleep"
internal const val ChaptersButtonTag = "player-chapters"

internal val ChapterButtonSize = 48.dp
internal val SkipButtonSize = 56.dp
internal val PlayButtonSize = 72.dp
private val SkipIconSize = 32.dp
private val PlayIconSize = 34.dp
private val BufferingRing = 80.dp
private val TileMinHeight = 56.dp
internal val TileIconSize = 20.dp
private val TileGap = 8.dp

/**
 * The interval inside the skip arrow: a part of the icon, as large whatever the text size, so that
 * it always fits inside the arrow. The arrow's centre is a little below the icon's.
 */
private val SkipDigitsSize = 10.dp
private val SkipDigitsDrop = 1.3.dp

/**
 * Previous chapter, back, play or pause, forward, next chapter; each says what it does, the skips
 * with their intervals. A finished book has no play button that would do nothing: it offers its
 * rating instead, or, from a source without ratings, its end panel under the name "Book finished".
 */
@Composable
internal fun TransportControls(
    transport: Transport,
    controls: PlaybackControls,
    onRate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChapterButton(Icons.Rounded.SkipPrevious, R.string.player_button_previous_chapter, controls::previousChapter)
        SkipButton(transport.backSeconds, R.string.player_button_back, mirrored = false, controls::back)
        if (transport.finished && transport.ratings) {
            RoundButton(Icons.Rounded.Star, stringResource(R.string.player_rate), onRate)
        } else if (transport.finished) {
            RoundButton(Icons.Rounded.Done, stringResource(R.string.player_end_finished), onRate)
        } else {
            Box(contentAlignment = Alignment.Center) {
                if (transport.buffering) CircularProgressIndicator(Modifier.size(BufferingRing))
                RoundButton(
                    icon = if (transport.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    label = stringResource(if (transport.playing) R.string.player_pause else R.string.player_play),
                    onClick = controls::playPause,
                )
            }
        }
        SkipButton(transport.forwardSeconds, R.string.player_button_forward, mirrored = true, controls::forward)
        ChapterButton(Icons.Rounded.SkipNext, R.string.player_button_next_chapter, controls::nextChapter)
    }
}

@Composable
private fun ChapterButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(ChapterButtonSize)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** A circular arrow with its interval drawn inside it; a screen reader hears the button's label. */
@Composable
internal fun SkipButton(seconds: Int, label: Int, mirrored: Boolean, onClick: () -> Unit) {
    val spoken = stringResource(label, seconds)
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(SkipButtonSize)
            .semantics { contentDescription = spoken },
    ) {
        val measurer = rememberTextMeasurer()
        val colour = LocalContentColor.current
        val digits = TextStyle(
            fontSize = with(LocalDensity.current) { SkipDigitsSize.toSp() },
            fontWeight = FontWeight.SemiBold,
        )
        val drop = with(LocalDensity.current) { SkipDigitsDrop.toPx() }
        Box(
            Modifier
                .size(SkipIconSize)
                .drawWithContent {
                    drawContent()
                    val text = measurer.measure(seconds.toString(), digits)
                    val corner = Offset(
                        (size.width - text.size.width) / 2,
                        (size.height - text.size.height) / 2 + drop,
                    )
                    drawText(text, colour, corner)
                },
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { if (mirrored) scaleX = -1f },
            )
        }
    }
}

@Composable
internal fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(PlayButtonSize)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(PlayIconSize))
    }
}

/**
 * The speed, the sleep timer, the chapters and the bookmark: tiles sharing the width, each a value or an icon
 * over its name ([TileRow]: no value is cut short). The sleep timer shows its time left [sleepLeft], in the
 * accent colour, while it runs ([sleeping]). [onChapters] null leaves the chapters out, where the list is always
 * shown; [chaptersName] is what that tile is called (a narration's are its sections, a book in parts has parts).
 * [onBookmark] null leaves the bookmark out: a narration has none.
 */
@Composable
internal fun BottomRow(
    speed: Float,
    sleepLeft: String?,
    sleeping: Boolean,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onChapters: (() -> Unit)?,
    modifier: Modifier = Modifier,
    @StringRes chaptersName: Int = R.string.player_chapters,
    onBookmark: (() -> Unit)? = null,
) {
    TileRow(modifier.fillMaxWidth()) {
        Tile(stringResource(R.string.player_speed), onSpeed, Modifier.testTag(SpeedButtonTag)) {
            SingleLineLabel(speedText(speed))
        }
        Tile(stringResource(R.string.player_sleep), onSleep, Modifier.testTag(SleepButtonTag), active = sleeping) {
            if (sleepLeft != null) {
                SingleLineLabel(sleepLeft)
            } else {
                Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(TileIconSize))
            }
        }
        onChapters?.let {
            Tile(stringResource(chaptersName), it, Modifier.testTag(ChaptersButtonTag)) {
                Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null, modifier = Modifier.size(TileIconSize))
            }
        }
        onBookmark?.let { BookmarkTile(it) }
    }
}

/**
 * Tiles as wide as each other, sharing the width: all on one line when each has room there for what it shows,
 * otherwise on as few lines as that allows, as evenly filled as can be (two and two rather than three and one).
 * The tiles of a line are as high as the highest.
 */
@Composable
private fun TileRow(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { tiles, constraints ->
        val gap = TileGap.roundToPx()
        if (tiles.isEmpty()) return@Layout layout(constraints.minWidth, 0) {}
        val widest = tiles.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (widest + gap) * tiles.size
        // The most a line holds, each tile as wide as the widest needs; then as many on each line as can be.
        val most = (tiles.size downTo 1).firstOrNull { (width - gap * (it - 1)) / it >= widest } ?: 1
        val lines = (tiles.size + most - 1) / most
        val placed = tiles.chunked((tiles.size + lines - 1) / lines).map { line ->
            val tileWidth = (width - gap * (line.size - 1)) / line.size
            val height = line.maxOf { it.minIntrinsicHeight(tileWidth) }
            line.map { it.measure(Constraints.fixed(tileWidth, height)) }
        }
        val height = placed.sumOf { line -> line.maxOf { it.height } } + gap * (placed.size - 1)
        layout(width, height) {
            var y = 0
            placed.forEach { line ->
                var x = 0
                line.forEach {
                    it.placeRelative(x, y)
                    x += it.width + gap
                }
                y += line.maxOf { it.height } + gap
            }
        }
    }
}

/**
 * What is left of the sleep timer: its minutes, or the time to the end of the chapter at the speed;
 * "End of chapter" ([names]) when that is not known. Null without a timer.
 */
@Composable
internal fun sleepLeftText(state: PlayerUiState, names: SleepNames = ChapterSleep): String? {
    val sleep = state.sleep ?: return null
    val left = sleep.remainingMs ?: state.chapterDurationMs
        ?.takeIf { sleep.choice == SleepChoice.EndOfChapter }
        ?.let { timeLeft(it, state.chapterElapsedMs, state.speed) }
    return when {
        left != null -> durationText(left)
        sleep.choice == SleepChoice.EndOfChapter -> stringResource(names.endOf)
        else -> null
    }
}

@Composable
internal fun Tile(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier,
    active: Boolean = false,
    value: @Composable () -> Unit,
) {
    val colours = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = TileMinHeight)
            .semantics { role = Role.Button },
        shape = RoundedCornerShape(14.dp),
        color = if (active) colours.surfaceContainerHighest else colours.surfaceContainer,
        contentColor = if (active) colours.primary else colours.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            // Whole words of a value ("44 min") are not where it may break: the row asks for its whole width, so that a
            // tile without room for it goes under the others instead of cutting it short.
            Box(Modifier.width(IntrinsicSize.Max), contentAlignment = Alignment.Center) {
                ProvideTextStyle(MaterialTheme.typography.titleSmall) { value() }
            }
            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                SingleLineLabel(
                    text = name,
                    modifier = Modifier.width(IntrinsicSize.Max),
                    color = if (active) colours.primary else colours.onSurfaceVariant,
                )
            }
        }
    }
}
