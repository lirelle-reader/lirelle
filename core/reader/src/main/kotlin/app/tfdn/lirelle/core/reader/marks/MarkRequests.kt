package app.tfdn.lirelle.core.reader.marks

import app.tfdn.lirelle.core.library.MarksRepository
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Duration

/**
 * How far before the stamp of an add a highlight the server holds may have been made and still be
 * that add: the server dates it by its own clock, which may be behind the device's.
 */
private val ClockTolerance = Duration.ofMinutes(2)

/** Sends a change of an item the server knows as [serverId]. Adds have routes of their own. */
internal suspend fun MarksRepository.send(change: MarkChange, serverId: ServerMarkId): ApiResult<*> = when (change) {
    is MarkChange.ChangeHighlight -> changeHighlight(
        bookId = change.bookId,
        id = serverId,
        color = change.color,
        note = (change.note as? NoteEdit.Set)?.text,
        clearNote = change.note == NoteEdit.Clear,
    )
    is MarkChange.DeleteHighlight -> deleteHighlight(change.bookId, serverId)
    is MarkChange.RestoreHighlight -> restoreHighlight(serverId)
    is MarkChange.DeleteBookmark -> deleteBookmark(change.bookId, serverId)
    is MarkChange.AddHighlight, is MarkChange.AddBookmark -> error("An add has no server id to be sent to")
}

/**
 * The server id of the highlight a send of [add] may have made, if the server holds one: the same
 * place and text, made no earlier than [ClockTolerance] before the stamp, and not already bound to
 * another item here. Items a refresh brought (`h<serverId>`) are not bound to anything.
 */
internal suspend fun MarksRepository.duplicateOf(
    add: MarkChange.AddHighlight,
    held: HeldBook,
): ApiResult<ServerMarkId?> {
    val since = add.attemptedAt?.minus(ClockTolerance) ?: return ApiResult.Success(null)
    val bound = held.highlights
        .filter { it.id != add.id && it.serverId?.let(MarkId::ofHighlight) != it.id }
        .mapNotNull { it.serverId }
        .toSet()
    return when (val list = highlights(add.bookId, add.fileId)) {
        is ApiResult.Failure -> list
        is ApiResult.Success -> ApiResult.Success(
            list.value.firstOrNull {
                it.cfi == add.highlight.cfi && it.text == add.highlight.text &&
                    !it.createdAt.isBefore(since) && it.id !in bound
            }?.id,
        )
    }
}

/** The server id of the item [change] is about, shown or deleted; null when it has none. */
internal fun HeldBook.serverIdOf(change: MarkChange): ServerMarkId? = when (change) {
    is MarkChange.AddBookmark, is MarkChange.DeleteBookmark -> bookmarks.firstOrNull { it.id == change.id }?.serverId
    else -> highlights.firstOrNull { it.id == change.id }?.serverId
}

/** An add of the item [change] is about. */
internal fun MarkChange.addsItemOf(change: MarkChange) =
    (this is MarkChange.AddHighlight || this is MarkChange.AddBookmark) && sameItem(change)
