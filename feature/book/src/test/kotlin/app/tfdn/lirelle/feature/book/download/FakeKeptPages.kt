package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading

/** The pages the device keeps, by book id; what is kept again is noted in [keeps]. */
class FakeKeptPages : KeptPages {
    val pages = mutableMapOf<BookId, KeptPage>()
    val keeps = mutableListOf<Pair<BookDetail, BookReading?>>()

    override suspend fun page(bookId: BookId): KeptPage? = pages[bookId]

    override suspend fun keep(detail: BookDetail, reading: BookReading?) {
        keeps += detail to reading
    }
}
