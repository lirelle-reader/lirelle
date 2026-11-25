package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.model.ServerLibrary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The fake follows the rules of the stored choice: a screen tested with it behaves as with the real one. */
class FakeLibraryChoiceTest {

    private val novels = ServerLibrary(LibraryId("1"), "Novels")
    private val comics = ServerLibrary(LibraryId("2"), "Comics")

    @Test
    fun `it starts with what it is given, nothing by default`() {
        assertThat(FakeLibraryChoice().state.value).isEqualTo(LibraryChoiceState())
        assertThat(FakeLibraryChoice(LibraryChoiceState(listOf(novels), novels)).state.value)
            .isEqualTo(LibraryChoiceState(listOf(novels), novels))
    }

    @Test
    fun `the libraries just read are kept`() = runTest {
        val choice = FakeLibraryChoice()

        choice.libraries(listOf(novels, comics))

        assertThat(choice.state.value).isEqualTo(LibraryChoiceState(listOf(novels, comics)))
    }

    @Test
    fun `the chosen library takes the name its server gave it since`() = runTest {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(novels, comics), comics))
        val renamed = comics.copy(name = "Bandes dessinées")

        choice.libraries(listOf(novels, renamed))

        assertThat(choice.state.value).isEqualTo(LibraryChoiceState(listOf(novels, renamed), renamed))
    }

    @Test
    fun `a chosen library missing from the libraries just read is forgotten, and stays so`() = runTest {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(novels, comics), comics))

        choice.libraries(listOf(novels))
        assertThat(choice.state.value).isEqualTo(LibraryChoiceState(listOf(novels)))

        choice.libraries(listOf(novels, comics))
        assertThat(choice.state.value).isEqualTo(LibraryChoiceState(listOf(novels, comics)))
    }

    @Test
    fun `choosing every library keeps the libraries`() = runTest {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(novels, comics), novels))

        choice.choose(null)

        assertThat(choice.state.value).isEqualTo(LibraryChoiceState(listOf(novels, comics)))
    }

    @Test
    fun `a library that is not among those kept can still be chosen`() = runTest {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(novels)))

        choice.choose(comics)

        assertThat(choice.state.value).isEqualTo(LibraryChoiceState(listOf(novels), comics))
    }
}
