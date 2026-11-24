package app.tfdn.lirelle.feature.book

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

// ---------- What a reading action's button writes, and what a screen reader says of it ----------

/** The name of the action, under its disc. */
@Composable
internal fun ActionKind.label(): String =
    stringResource(if (this == ActionKind.Read) R.string.action_read else R.string.action_listen)

/** The status written under the name; null when the button says nothing more than its name. */
@Composable
internal fun ActionRingUi.statusText(): String? = when (val status = status) {
    RingStatus.None -> null
    RingStatus.Start -> stringResource(R.string.status_start)
    RingStatus.Resume -> stringResource(R.string.status_resume)
    is RingStatus.ResumeAt -> stringResource(R.string.status_resume_percent, status.percent)
    RingStatus.Again -> stringResource(
        if (kind == ActionKind.Read) R.string.status_read_again else R.string.status_listen_again,
    )
    RingStatus.Offline -> stringResource(R.string.status_offline)
    RingStatus.Unavailable -> stringResource(R.string.status_unavailable)
}

/** The status as it is said aloud: in words, without the dot that separates its two parts on screen. */
@Composable
private fun ActionRingUi.spokenStatus(): String? = when (val status = status) {
    RingStatus.None -> null
    RingStatus.Start -> stringResource(R.string.status_start_spoken)
    RingStatus.Resume -> stringResource(R.string.status_resume_spoken)
    is RingStatus.ResumeAt -> stringResource(R.string.status_resume_percent_spoken, status.percent)
    RingStatus.Again -> stringResource(
        if (kind == ActionKind.Read) R.string.status_read_again_spoken else R.string.status_listen_again_spoken,
    )
    RingStatus.Offline -> stringResource(R.string.status_offline_spoken)
    RingStatus.Unavailable -> stringResource(R.string.status_unavailable_spoken)
}

/**
 * What a screen reader says of the button: its name, its status, and that the reader can narrate the book,
 * which the icon alone says on screen. "Read, resume at 62%, synchronised narration available".
 */
@Composable
internal fun ActionRingUi.spoken(): String {
    val narration = if (narrated) stringResource(R.string.action_ring_narration) else null
    return listOfNotNull(kind.label(), spokenStatus(), narration).joinToString()
}
