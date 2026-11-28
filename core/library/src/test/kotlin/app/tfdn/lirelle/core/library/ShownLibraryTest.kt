package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.model.ServerLibrary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The library the Library's lists show: the profile's choice, or, for a source whose Library always shows one
 * library, the first of the server's when the choice is none or no longer among them.
 */
class ShownLibraryTest {

    private val comics = ServerLibrary(LibraryId("1"), "Comics")
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val novels = ServerLibrary(LibraryId("3"), "Novels")
    private val alwaysOne = SourceCapabilities.Everything.copy(libraryAlwaysChosen = true)

    @Test
    fun `a source keeps all of its libraries together unless it says otherwise`() {
        assertThat(SourceCapabilities.Everything.libraryAlwaysChosen).isFalse()
    }

    @Test
    fun `a source that can show all libraries shows the choice as it is`() {
        val everything = SourceCapabilities.Everything

        assertThat(LibraryChoiceState(listOf(comics, manga)).shownLibrary(everything)).isNull()
        assertThat(LibraryChoiceState(listOf(comics, manga), manga).shownLibrary(everything)).isEqualTo(manga)
        assertThat(LibraryChoiceState(listOf(comics, manga), novels).shownLibrary(everything)).isEqualTo(novels)
    }

    @Test
    fun `nothing chosen shows the first library`() {
        assertThat(LibraryChoiceState(listOf(comics, manga)).shownLibrary(alwaysOne)).isEqualTo(comics)
    }

    @Test
    fun `the chosen library is shown as the list has it`() {
        val renamed = manga.copy(name = "Mangas")

        assertThat(LibraryChoiceState(listOf(comics, renamed), manga).shownLibrary(alwaysOne)).isEqualTo(renamed)
    }

    @Test
    fun `a chosen library no longer in the list falls back to the first`() {
        assertThat(LibraryChoiceState(listOf(comics, manga), novels).shownLibrary(alwaysOne)).isEqualTo(comics)
    }

    @Test
    fun `a single library is shown, never all of them`() {
        assertThat(LibraryChoiceState(listOf(novels)).shownLibrary(alwaysOne)).isEqualTo(novels)
    }

    @Test
    fun `with no library read yet the choice is all there is`() {
        assertThat(LibraryChoiceState().shownLibrary(alwaysOne)).isNull()
        assertThat(LibraryChoiceState(chosen = manga).shownLibrary(alwaysOne)).isEqualTo(manga)
    }

    @Test
    fun `the shown library changes only when another one is shown`() = runTest {
        val states = flowOf(
            LibraryChoiceState(listOf(comics, manga)),
            LibraryChoiceState(listOf(comics, manga, novels)),
            LibraryChoiceState(listOf(comics, manga), manga),
        )
        val choice = object : LibraryChoice {
            override val state = states
            override suspend fun choose(library: ServerLibrary?) = Unit
            override suspend fun libraries(libraries: List<ServerLibrary>) = Unit
        }

        assertThat(choice.shownLibrary(alwaysOne).toList()).containsExactly(comics, manga).inOrder()
        assertThat(choice.shownLibrary(SourceCapabilities.Everything).first()).isNull()
    }
}
