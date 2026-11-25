package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.library.LibraryChoice
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.model.ServerLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** [LibraryChoice] kept in memory, with the rules of the stored one. A test reads what it holds in [state]. */
class FakeLibraryChoice(initial: LibraryChoiceState = LibraryChoiceState()) : LibraryChoice {
    override val state = MutableStateFlow(initial)

    override suspend fun choose(library: ServerLibrary?) = state.update { it.copy(chosen = library) }

    override suspend fun libraries(libraries: List<ServerLibrary>) = state.update { held ->
        LibraryChoiceState(libraries, libraries.firstOrNull { it.id == held.chosen?.id })
    }
}
