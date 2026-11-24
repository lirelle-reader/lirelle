package app.tfdn.lirelle.feature.player.narration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.core.designsystem.SingleLineLabel
import app.tfdn.lirelle.feature.player.R
import kotlinx.coroutines.delay

/** How long a notice stays, unless the accessibility settings ask for longer. */
private const val NoticeShownMs = 8_000L
private val NoticeCorner = 12.dp
private val NoticeElevation = 6.dp
private val TouchTarget = 48.dp

/**
 * A section without narration the narration went past ([notice]), told on a card in the reader's colours inverted
 * (its text colour as the background), above the bar: "“Interlude” has no narration: it was skipped", or without a
 * title "A section without narration was skipped", and "Read", which reads it ([onRead]). It asks to go after
 * 8 seconds, or the longer time the accessibility settings ask for ([onTimeout]); a screen reader is told it
 * politely.
 */
@Composable
fun NarrationNotice(
    notice: SkippedNotice,
    colors: NarrationColors,
    onRead: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeout by rememberUpdatedState(onTimeout)
    // As Material's snackbar: a user who asked for more time to act gets it.
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notice, accessibility) {
        val shown = accessibility?.calculateRecommendedTimeoutMillis(
            NoticeShownMs,
            containsIcons = false,
            containsText = true,
            containsControls = true,
        ) ?: NoticeShownMs
        delay(shown)
        timeout()
    }
    val text = notice.title?.let { stringResource(R.string.narration_skipped_titled, it) }
        ?: stringResource(R.string.narration_skipped)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            // The message is the card's: the live region says it; "Read" stays a button of its own.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(NoticeCorner),
        color = colors.foreground,
        contentColor = colors.background,
        shadowElevation = NoticeElevation,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onRead,
                modifier = Modifier.heightIn(min = TouchTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.background),
            ) {
                SingleLineLabel(stringResource(R.string.narration_skipped_read))
            }
        }
    }
}
