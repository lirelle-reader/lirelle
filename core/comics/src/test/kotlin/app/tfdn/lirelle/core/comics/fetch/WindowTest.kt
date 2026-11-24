package app.tfdn.lirelle.core.comics.fetch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WindowTest {

    @Test
    fun `forward comes first, the page before close behind`() {
        assertThat(windowOrder(10, pageCount = 50)).containsExactly(10, 11, 12, 9, 13, 8, 14, 7, 15, 6, 5).inOrder()
    }

    @Test
    fun `at the start of an album only what follows is in the window`() {
        assertThat(windowOrder(0, pageCount = 50)).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
    }

    @Test
    fun `one page in keeps the page before in its place`() {
        assertThat(windowOrder(1, pageCount = 50)).containsExactly(1, 2, 3, 0, 4, 5, 6).inOrder()
    }

    @Test
    fun `at the end of an album only what precedes is in the window`() {
        assertThat(windowOrder(49, pageCount = 50)).containsExactly(49, 48, 47, 46, 45, 44).inOrder()
    }

    @Test
    fun `a short album is all in the window`() {
        assertThat(windowOrder(1, pageCount = 3)).containsExactly(1, 2, 0).inOrder()
    }

    @Test
    fun `a page outside the album has no place in its own window`() {
        assertThat(windowOrder(7, pageCount = 5)).containsExactly(4, 3, 2).inOrder()
    }
}
