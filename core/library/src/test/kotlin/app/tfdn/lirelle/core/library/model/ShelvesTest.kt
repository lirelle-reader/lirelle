package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.ShelfId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShelvesTest {

    private val playlist = Shelf(ShelfKind.Playlist, ShelfId("p:1"), "Road trip", 3)

    @Test
    fun `shelves have no playlists unless a source gives some`() {
        assertThat(Shelves(emptyList(), emptyList()).playlists).isEmpty()
    }

    @Test
    fun `playlists alone are shelves`() {
        assertThat(Shelves(emptyList(), emptyList()).isEmpty).isTrue()
        assertThat(Shelves(emptyList(), emptyList(), listOf(playlist)).isEmpty).isFalse()
    }
}
