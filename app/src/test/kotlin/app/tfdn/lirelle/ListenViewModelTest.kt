package app.tfdn.lirelle

import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ListenViewModelTest {

    private val loaded = mutableListOf<Pair<BookId, Boolean>>()
    private val viewModel = ListenViewModel { bookId, fromStart -> loaded += bookId to fromStart }

    @Test
    fun `listening loads the book in the player, from where it was left or from its start`() {
        viewModel.listen(BookId("7"), fromStart = false)
        viewModel.listen(BookId("8"), fromStart = true)

        assertThat(loaded).containsExactly(BookId("7") to false, BookId("8") to true).inOrder()
    }
}
