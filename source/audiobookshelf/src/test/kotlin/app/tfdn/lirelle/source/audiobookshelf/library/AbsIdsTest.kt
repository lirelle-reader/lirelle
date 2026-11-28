package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ShelfId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val Uuid = "2bffab16-8cc2-41e9-bfb5-fb0640571fcf"

/** The app's ids are Audiobookshelf's UUIDs as they are; a shelf's says whether it is a collection or a playlist. */
class AbsIdsTest {

    @Test
    fun `an item's UUID is its book's id, and back`() {
        assertThat(Uuid.asBookId()).isEqualTo(BookId(Uuid))
        assertThat(BookId(Uuid).absId).isEqualTo(Uuid)
    }

    @Test
    fun `a collection and a playlist are told apart by their shelf ids`() {
        assertThat(Uuid.asCollectionShelfId()).isEqualTo(ShelfId("c:$Uuid"))
        assertThat(Uuid.asPlaylistShelfId()).isEqualTo(ShelfId("p:$Uuid"))
        assertThat(ShelfId("c:$Uuid").absShelf()).isEqualTo(AbsShelf.Collection(Uuid))
        assertThat(ShelfId("p:$Uuid").absShelf()).isEqualTo(AbsShelf.Playlist(Uuid))
    }

    @Test
    fun `an id that cannot be Audiobookshelf's is none`() {
        assertThat(BookId("").absId).isNull()
        assertThat(BookId("../users").absId).isNull()
        assertThat(ShelfId(Uuid).absShelf()).isNull()
        assertThat(ShelfId("c:").absShelf()).isNull()
        assertThat(ShelfId("x:$Uuid").absShelf()).isNull()
    }
}
