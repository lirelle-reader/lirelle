package app.tfdn.lirelle.source.bookorbit.di

import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ShelfKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** BookOrbit can do everything the app shows but playlists, which it has none of: nothing else is hidden. */
class BookOrbitCapabilitiesTest {

    @Test
    fun `BookOrbit declares that it can do everything`() {
        assertThat(BookOrbitLibraryModule.capabilities()).isEqualTo(SourceCapabilities.Everything)
    }

    @Test
    fun `BookOrbit has collections, no playlist`() {
        assertThat(BookOrbitLibraryModule.capabilities().shelfKinds).doesNotContain(ShelfKind.Playlist)
        assertThat(BookOrbitLibraryModule.capabilities().shelfKinds).contains(ShelfKind.Collection)
    }

    @Test
    fun `a BookOrbit library opens on its books, as it always did`() {
        assertThat(BookOrbitLibraryModule.capabilities().libraryStart).isEqualTo(LibraryStart.Books)
    }
}
