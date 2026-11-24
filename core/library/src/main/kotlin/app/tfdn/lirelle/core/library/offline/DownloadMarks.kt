package app.tfdn.lirelle.core.library.offline

import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What a cover says of the book on this device: kept, being fetched, or kept with a newer version to fetch. */
enum class DownloadMark { Kept, InProgress, Update }

/** The mark of each book that has one, by book id. A book the device keeps nothing of is absent. */
interface DownloadMarks {
    val marks: Flow<Map<BookId, DownloadMark>>

    /** Nothing is kept, so nothing is marked. */
    object None : DownloadMarks {
        override val marks: Flow<Map<BookId, DownloadMark>> = flowOf(emptyMap())
    }
}
