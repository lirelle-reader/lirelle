package app.tfdn.lirelle.core.downloads.model

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.PagedKind

/**
 * What a kept file is on the server's side, as the planner knows it: enough to ask where it is served when the
 * download runs (see `DownloadLocations`), and nothing of the address itself. A record that names no server
 * means the same whichever server, or source, its profile is of, and the address is never read back out of it.
 * The book is the record's own, and named by none of these.
 */
sealed interface FileSource {
    /** The file of a book as it is: a book's text in its own format. */
    data class Whole(val fileId: FileId) : FileSource

    /** The EPUB of a read-along file without its audio, which the server builds when it is asked. */
    data class Audioless(val fileId: FileId) : FileSource

    /** The page at [index], from 0, of a file the server serves by pages of this [kind], a comic or a DjVu. */
    data class Page(val fileId: FileId, val kind: PagedKind, val index: Int) : FileSource

    /** One file of an audiobook, by the asset the manifest names. */
    data class AudioAsset(val assetId: String) : FileSource

    /** One audio file of a read-along file's narration, by its path ([href]) in the EPUB. */
    data class ReadAlongTrack(val fileId: FileId, val href: String) : FileSource
}
