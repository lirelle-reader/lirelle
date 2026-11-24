package app.tfdn.lirelle.core.comics.store

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoundTest {

    private fun album(name: String, bytes: Long, openedAt: Long, held: Boolean = false) =
        KeptAlbum(name, bytes, openedAt, held)

    @Test
    fun `nothing goes under the limit`() {
        val albums = listOf(album("a", 200, openedAt = 1), album("b", 300, openedAt = 2))

        assertThat(albumsToDrop(albums, limitBytes = 500)).isEmpty()
    }

    @Test
    fun `the album opened longest ago goes first`() {
        val albums = listOf(album("b", 300, openedAt = 2), album("a", 300, openedAt = 1), album("c", 300, openedAt = 3))

        assertThat(albumsToDrop(albums, limitBytes = 700)).containsExactly("a")
    }

    @Test
    fun `albums go until the rest fits`() {
        val albums = listOf(album("a", 300, openedAt = 1), album("b", 300, openedAt = 2), album("c", 300, openedAt = 3))

        assertThat(albumsToDrop(albums, limitBytes = 350)).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `an album held by a reader never goes`() {
        val albums = listOf(
            album("a", 300, openedAt = 1, held = true),
            album("b", 300, openedAt = 2),
            album("c", 300, openedAt = 3),
        )

        assertThat(albumsToDrop(albums, limitBytes = 700)).containsExactly("b")
    }

    @Test
    fun `an album held alone above the limit stays`() {
        assertThat(albumsToDrop(listOf(album("a", 900, openedAt = 1, held = true)), limitBytes = 500)).isEmpty()
    }
}
