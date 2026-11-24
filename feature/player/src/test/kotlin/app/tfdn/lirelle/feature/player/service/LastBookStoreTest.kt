package app.tfdn.lirelle.feature.player.service

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LastBookStoreTest {

    private val file = MemoryPreferences()
    private val mark = newLastBookMark()
    private val store = lastBookStore(file, mark)
    private val book = LastBook(BookId("42"), "The Long Road", "https://books.example/c/42")

    @Test
    fun `the last book is kept, and a new instance reads it`() = runTest {
        store.put(book)

        assertThat(store.get()).isEqualTo(book)
        assertThat(lastBookStore(file).get()).isEqualTo(book)
    }

    @Test
    fun `a book without a cover replaces one that had a cover`() = runTest {
        store.put(book)

        store.put(book.copy(bookId = BookId("43"), coverUrl = null))

        assertThat(store.get()).isEqualTo(LastBook(BookId("43"), "The Long Road", null))
    }

    @Test
    fun `a book stored with the server and user of before still reads, and is written again without them`() =
        runTest {
            file.edit {
                it[stringPreferencesKey("server")] = "https://books.example"
                it[longPreferencesKey("user")] = 8
                it[stringPreferencesKey("book")] = "42"
                it[stringPreferencesKey("title")] = "The Long Road"
                it[stringPreferencesKey("cover")] = "https://books.example/c/42"
            }

            assertThat(store.get()).isEqualTo(book)

            store.put(book.copy(bookId = BookId("43")))
            assertThat(store.get()).isEqualTo(book.copy(bookId = BookId("43")))
            assertThat(file.data.first().asMap().keys.map { it.name }).containsExactly("book", "title", "cover")
        }

    /** Kept as a number, before the ids were text: no book to resume, and no failure. */
    @Test
    fun `a book kept as a number is none`() = runTest {
        file.edit {
            it[longPreferencesKey("book")] = 42
            it[stringPreferencesKey("title")] = "The Long Road"
        }

        assertThat(store.get()).isNull()
    }

    @Test
    fun `clear forgets it`() = runTest {
        store.put(book)

        store.clear()

        assertThat(store.get()).isNull()
    }

    @Test
    fun `the mark is there while a book is kept, and follows each write`() = runTest {
        assertThat(mark.exists()).isFalse()

        store.put(book)
        assertThat(mark.exists()).isTrue()

        store.put(book.copy(bookId = BookId("43")))
        assertThat(mark.exists()).isTrue()

        store.clear()
        assertThat(mark.exists()).isFalse()
    }

    /** The mark is a file of the profile's folder: nothing has to be read before it answers. */
    @Test
    fun `a profile that opens with a book kept knows it at once`() = runTest {
        val folder = java.nio.file.Files.createTempDirectory("profile").toFile()
        lastBookStore(file, LastBookMark(java.io.File(folder, "last_book.kept"))).put(book)

        val opened = LastBookMark(java.io.File(folder, "last_book.kept"))

        assertThat(opened.exists()).isTrue()
    }
}
