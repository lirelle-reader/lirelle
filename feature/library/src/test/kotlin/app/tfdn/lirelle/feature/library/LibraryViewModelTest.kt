package app.tfdn.lirelle.feature.library

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryChoice
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The Library reads the libraries of its server, keeps them with the profile's choice, and stores a new choice. */
class LibraryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val comics = ServerLibrary(LibraryId("1"), "Comics")
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val novels = ServerLibrary(LibraryId("3"), "Novels")
    private val repository = FakeLibraryRepository().apply {
        librariesResult = ApiResult.Success(listOf(comics, manga))
    }
    private val choice = FakeLibraryChoice()

    private fun viewModel(capabilities: SourceCapabilities = SourceCapabilities.Everything) =
        LibraryViewModel(capabilities, repository, choice)

    private val alwaysOne = SourceCapabilities.Everything.copy(libraryAlwaysChosen = true)

    private fun reads() = repository.calls.count { it == "libraries" }

    @Test
    fun `the libraries are read when the Library is created, and kept`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        assertThat(reads()).isEqualTo(1)
        assertThat(choice.state.value).isEqualTo(LibraryChoiceState(listOf(comics, manga)))
        assertThat(viewModel.libraries.value).isEqualTo(LibraryChoiceState(listOf(comics, manga)))
    }

    @Test
    fun `what was kept is shown before the server answers`() = runTest(mainRule.dispatcher) {
        choice.state.value = LibraryChoiceState(listOf(comics, novels), chosen = novels)
        repository.gate = CompletableDeferred()

        val viewModel = viewModel()

        assertThat(viewModel.libraries.value).isEqualTo(LibraryChoiceState(listOf(comics, novels), chosen = novels))
    }

    @Test
    fun `a failed read leaves the libraries and the choice as they were kept`() = runTest(mainRule.dispatcher) {
        val kept = LibraryChoiceState(listOf(comics, novels), chosen = novels)
        choice.state.value = kept
        repository.librariesResult = ApiResult.Failure(ApiError.ServerUnreachable)

        val viewModel = viewModel()

        assertThat(reads()).isEqualTo(1)
        assertThat(choice.state.value).isEqualTo(kept)
        assertThat(viewModel.libraries.value).isEqualTo(kept)
    }

    @Test
    fun `a failed read with nothing kept leaves no library to choose`() = runTest(mainRule.dispatcher) {
        repository.librariesResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(viewModel().libraries.value).isEqualTo(LibraryChoiceState())
    }

    @Test
    fun `the libraries are read again when the Library is shown again`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        repository.librariesResult = ApiResult.Success(listOf(comics, manga, novels))

        viewModel.refresh()

        assertThat(reads()).isEqualTo(2)
        assertThat(viewModel.libraries.value.libraries).containsExactly(comics, manga, novels).inOrder()
    }

    @Test
    fun `a Library shown while its libraries are being read does not ask twice`() = runTest(mainRule.dispatcher) {
        repository.gate = CompletableDeferred()
        val viewModel = viewModel()

        viewModel.refresh()
        repository.gate!!.complete(Unit)

        assertThat(reads()).isEqualTo(1)
        assertThat(viewModel.libraries.value.libraries).containsExactly(comics, manga).inOrder()
    }

    @Test
    fun `a chosen library that the server no longer has is forgotten`() = runTest(mainRule.dispatcher) {
        choice.state.value = LibraryChoiceState(listOf(comics, novels), chosen = novels)

        val viewModel = viewModel()

        assertThat(viewModel.libraries.value).isEqualTo(LibraryChoiceState(listOf(comics, manga)))
    }

    @Test
    fun `a chosen library renamed on the server is shown under its new name`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onChoose(comics)
        val renamed = comics.copy(name = "Bandes dessinées")
        repository.librariesResult = ApiResult.Success(listOf(renamed, manga))

        viewModel.refresh()

        assertThat(viewModel.libraries.value.chosen).isEqualTo(renamed)
    }

    @Test
    fun `choosing a library keeps it, and choosing all libraries forgets it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onChoose(manga)
        assertThat(choice.state.value.chosen).isEqualTo(manga)
        assertThat(viewModel.libraries.value.chosen).isEqualTo(manga)

        viewModel.onChoose(null)
        assertThat(choice.state.value.chosen).isNull()
    }

    @Test
    fun `a source that can show all libraries offers all of them`() {
        assertThat(viewModel().offersAllLibraries).isTrue()
    }

    @Test
    fun `a source whose Library always shows one library offers no all`() {
        assertThat(viewModel(alwaysOne).offersAllLibraries).isFalse()
    }

    @Test
    fun `with nothing chosen, the first library is shown, and the choice stays none`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(alwaysOne)

        assertThat(viewModel.libraries.value).isEqualTo(LibraryChoiceState(listOf(comics, manga), chosen = comics))
        assertThat(choice.state.value.chosen).isNull()
    }

    @Test
    fun `a chosen library no longer in the list shows the first one`() = runTest(mainRule.dispatcher) {
        choice.state.value = LibraryChoiceState(listOf(comics, manga), chosen = novels)
        repository.gate = CompletableDeferred()

        val viewModel = viewModel(alwaysOne)

        assertThat(viewModel.libraries.value.chosen).isEqualTo(comics)
        assertThat(choice.state.value.chosen).isEqualTo(novels)
    }

    @Test
    fun `a library chosen is shown when the Library always shows one`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(alwaysOne)

        viewModel.onChoose(manga)

        assertThat(viewModel.libraries.value.chosen).isEqualTo(manga)
    }
}
