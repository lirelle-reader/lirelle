package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The ids of what a car browses: text of the app's own, read back as the place or the book it names. */
class CarIdsTest {

    @Test
    fun `the root and the tabs have the ids the car is given`() {
        assertThat(CarIds.of(CarPlace.Root)).isEqualTo("root")
        assertThat(CarPlace.Tab.entries.map { CarIds.of(it) })
            .containsExactly("tab/home", "tab/downloaded", "tab/audiobooks", "tab/series").inOrder()
    }

    @Test
    fun `a place is found again from its id`() {
        val places = listOf(CarPlace.Root) + CarPlace.Tab.entries + CarPlace.OneSeries(SeriesId("4"))

        places.forEach { assertThat(CarIds.placeOf(CarIds.of(it))).isEqualTo(it) }
    }

    @Test
    fun `a node has the id of its place, a book its own`() {
        assertThat(CarIds.of(CarNode.Tab(CarPlace.Tab.Series))).isEqualTo("tab/series")
        assertThat(CarIds.of(CarNode.Series(SeriesId("4"), "A Saga", 2))).isEqualTo("series/4")
        assertThat(CarIds.of(CarNode.Book(BookId("42"), "The Long Road", null))).isEqualTo("book/42")
    }

    @Test
    fun `a book is found again from its id`() {
        assertThat(CarIds.bookOf(CarIds.book(BookId("42")))).isEqualTo(BookId("42"))
        assertThat(CarIds.bookOf("book/42")).isEqualTo(BookId("42"))
    }

    @Test
    fun `an id is text that means nothing here, whatever it holds`() {
        val book = BookId("0J4M/x y?é")
        val series = SeriesId("a/b/c")

        assertThat(CarIds.bookOf(CarIds.book(book))).isEqualTo(book)
        assertThat(CarIds.placeOf(CarIds.of(CarPlace.OneSeries(series)))).isEqualTo(CarPlace.OneSeries(series))
    }

    @Test
    fun `a book is not a place, nor a place a book`() {
        assertThat(CarIds.placeOf("book/42")).isNull()
        assertThat(CarIds.bookOf("series/4")).isNull()
        assertThat(CarIds.bookOf("tab/home")).isNull()
    }

    @Test
    fun `ids of elsewhere name nothing`() {
        // The player's own items: the last book, a chapter.
        val foreign =
            listOf("", "resume/42", "42/3", "42", "tab/", "tab/elsewhere", "series/", "book/", "Root", "root/")

        foreign.forEach {
            assertThat(CarIds.placeOf(it)).isNull()
            assertThat(CarIds.bookOf(it)).isNull()
        }
    }
}
