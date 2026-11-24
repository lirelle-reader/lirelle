package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WordsLabel
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.feature.book.download.BookDownloadUiState
import app.tfdn.lirelle.feature.book.download.DownloadActions
import app.tfdn.lirelle.feature.book.download.DownloadLine
import app.tfdn.lirelle.feature.book.download.DownloadTile

// ---------- 2. Actions (reading opens the reader, listening the player; the download tile and its line) ----------

const val MainActionTag = "book-main-action"
const val ListenActionTag = "book-listen-action"

/** The padding of the download tile: narrower at the sides than the default of a button. */
internal val SecondaryActionPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * Where the actions of the page lead: the reader or the player, on a book, from its start when it is done again;
 * the reader narrating the book, for one whose only narration is inside its read-along EPUB; the comics reader,
 * for a book that is a comic.
 */
internal class BookStarts(
    val onRead: (bookId: BookId, fromStart: Boolean) -> Unit,
    val onListen: (bookId: BookId, fromStart: Boolean) -> Unit,
    val onNarrate: (bookId: BookId) -> Unit,
    val onReadComic: (bookId: BookId, fromStart: Boolean) -> Unit = { _, _ -> },
)

/**
 * The downloads of the book, as the actions show them.
 *
 * @property kept on a page built without the server, the formats the device has whole: the only ones that
 *   open; null on a page the server gave.
 * @property gone that page is shown because the book is no longer on the server, not for lack of a network.
 */
internal class DownloadPart(
    val state: BookDownloadUiState = BookDownloadUiState(),
    val actions: DownloadActions = DownloadActions(),
    val kept: Set<DownloadFormat>? = null,
    val gone: Boolean = false,
) {
    /**
     * Whether the reader can have a read-along file, which the server only gives to an account that may
     * download: it may, or the device keeps the text of the book, on this page or on one built without the server.
     */
    val readAlongOpens: Boolean
        get() = state.allowed || state.keepsText || kept?.contains(DownloadFormat.Text) == true
}

/**
 * The reading actions are round buttons on one line, Read then Listen (see [ActionRingsRow]); each says under
 * its name whether it starts, resumes or does the book again, and its ring shows how far the user is. The one
 * of the mode used last is filled (see [filledKind]).
 *
 * Reading: Read opens the reader on the book, at its beginning when the book is finished and is read again. It
 * is enabled when the book has a file the reader opens. A comic has the same action, which opens the comics
 * reader, with the server only. Read opens the reader silently: the narration is started from the reader. When
 * the reader can narrate the book, the icon of Read says so.
 *
 * A book is finished when the server says so, a text by its read state and a listening by its being completed,
 * and by nothing else: where a library counts a book as finished is its own setting, which is not known here.
 * However far a book is, until then its action goes on from where it is.
 *
 * Listening: Listen is beside Read when the book has a text too, and is the only button of an audiobook. It
 * loads the book in the player and plays it, from its start when the listening is finished and is done again.
 * Until where the listening is arrives, it has no status and waits: a finished book must not be played on from
 * its end. When that cannot be had, it has no status and the player goes on from where it knows. A book whose
 * only narration is inside a read-along EPUB has no audio file to play: Listen opens the reader, which narrates
 * the book from where it opens. It has no status, the narration's own progress not being known here, and is
 * enabled when the reader can open the book.
 *
 * An account that may not download is given the plain EPUB of a read-along book, when it has one: the book then
 * has no narration here, neither the sound waves on the icon of Read nor the Listen that opens the reader. The
 * audio files of the book, if it has some, are listened to as ever.
 *
 * Under them, the download tile, in the middle of its own line, with its line under it. On a page built without
 * the server, an action whose format the device does not have whole is disabled and says so, and a line says it
 * waits for the network.
 */
@Composable
internal fun ActionsBlock(
    book: BookDetail,
    reading: BookReading?,
    listening: Loadable<PlaybackState?>,
    starts: BookStarts,
    download: DownloadPart = DownloadPart(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("book-actions"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionRingsRow(ringActions(book, reading, listening, starts, download))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DownloadTile(
                state = download.state.tile,
                onClick = download.actions.onTile,
                enabled = download.state.enabled,
                update = download.state.update,
            )
        }
        DownloadLine(
            state = download.state.tile,
            onAction = download.actions.onLineAction,
            allowed = download.state.allowed,
            update = download.state.update,
        )
        download.kept?.let { MissingFormats(book, it, download.gone) }
    }
}

/**
 * The buttons of the reading actions, by the rules of [ActionsBlock]: which exist, whether each is enabled,
 * what it shows and where it leads. The filled one is the mode used last, by the date of the reading position
 * and the date of the listening one, once the [reading] is there: until it arrives, and when it could not be
 * loaded, Read stays the filled one (see [filledKind]).
 */
internal fun ringActions(
    book: BookDetail,
    reading: BookReading?,
    listening: Loadable<PlaybackState?>,
    starts: BookStarts,
    download: DownloadPart,
): List<RingAction> {
    val openings = Openings(book, download.kept, download.readAlongOpens)
    val read = readAction(book, reading, openings, starts, download)
    val listen = listenAction(book, listening, openings, starts, download)
    val listenedAt = (listening as? Loadable.Ready)?.value?.capturedAt
    val filled = filledKind(read?.ui, listen?.ui, reading?.positionAt, listenedAt, readingArrived = reading != null)
    return listOfNotNull(read, listen).map { action ->
        RingAction(action.ui.copy(filled = action.ui.kind == filled), action.tag, action.onClick)
    }
}

/** Read, for a book that has a text: see [ActionsBlock]. */
private fun readAction(
    book: BookDetail,
    reading: BookReading?,
    openings: Openings,
    starts: BookStarts,
    download: DownloadPart,
): RingAction? {
    if (book.media == MediaProfile.AudioOnly) return null
    val finished = book.readState == ReadState.Read
    val status = readStatus(openings.read, finished, reading?.progressPercent ?: 0)
    val ring = actionRing(ActionKind.Read, openings.read, status, download.kept, download.gone)
    // The icon says what the reader can do now: without the server, only a narration the device keeps.
    return RingAction(ring.copy(narrated = openings.narrated && openings.narrate), MainActionTag) {
        if (book.isPaged) starts.onReadComic(book.id, finished) else starts.onRead(book.id, finished)
    }
}

/** Listen, for a book that has something to listen to: see [ActionsBlock]. */
private fun listenAction(
    book: BookDetail,
    listening: Loadable<PlaybackState?>,
    openings: Openings,
    starts: BookStarts,
    download: DownloadPart,
): RingAction? {
    val status = listenStatus(playable = book.hasAudioFiles, listening)
    return when {
        !book.hasAudioFiles && book.media == MediaProfile.ReadAlong -> {
            // The narration is the only audio of the book: nothing to listen to when the reader cannot play it.
            val ring = actionRing(ActionKind.Listen, openings.narrate, status, download.kept, download.gone)
            RingAction(ring, ListenActionTag) { starts.onNarrate(book.id) }.takeIf { openings.narrated }
        }
        book.media != MediaProfile.TextOnly -> {
            val listenedThrough = (listening as? Loadable.Ready)?.value?.completed == true
            val ring = actionRing(ActionKind.Listen, openings.listen, status, download.kept, download.gone)
            RingAction(if (listening == Loadable.Loading) ring.waiting() else ring, ListenActionTag) {
                starts.onListen(book.id, listenedThrough)
            }
        }
        else -> null
    }
}

/**
 * As wide as its place, and as tall as its label needs: the label is never cut short. Each word is a text of
 * one line, and the words that do not fit go on further lines; a screen reader is given the label whole. The
 * main action of its place is [filled].
 */
@Composable
internal fun WideAction(label: String, tag: String, enabled: Boolean, filled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier
        .fillMaxWidth()
        .testTag(tag)
        .semantics { contentDescription = label }
    val content: @Composable RowScope.() -> Unit = { WordsLabel(label) }
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, content = content)
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, content = content)
    }
}
