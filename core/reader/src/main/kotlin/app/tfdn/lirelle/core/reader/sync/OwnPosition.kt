package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId

/**
 * What this device holds of the text position of one file.
 *
 * @property stored the last one kept.
 * @property queued the one still to reach the server, if any.
 */
data class OwnPosition(val stored: LocalPosition?, val queued: LocalPosition?) {
    /** The most recent of the two. */
    val latest: LocalPosition? get() = listOfNotNull(stored, queued).maxByOrNull { it.at }
}

/** The text position of [fileId] as this device knows it, sent or not. */
suspend fun ownPosition(fileId: FileId, store: PositionStore, outbox: Outbox): OwnPosition {
    val queued = outbox.pending().positions.lastOrNull { it.fileId == fileId }
    return OwnPosition(stored = store.get(fileId), queued = queued)
}
