package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.library.model.ServerLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The library the Library destination shows, null for all of them. It is the profile's choice, unless the source
 * always shows one library ([SourceCapabilities.libraryAlwaysChosen]): then it is the chosen library as the list
 * has it, or the first of the list when none is chosen or the chosen one is no longer among them. That fallback is
 * never written into the choice: the user's choice stays what it was. With no list read yet, the choice is all
 * there is.
 */
fun LibraryChoiceState.shownLibrary(capabilities: SourceCapabilities): ServerLibrary? =
    if (capabilities.libraryAlwaysChosen) {
        libraries.firstOrNull { it.id == chosen?.id } ?: libraries.firstOrNull() ?: chosen
    } else {
        chosen
    }

/** The library shown as the choice changes: a new value only when another library, or another name, is shown. */
fun LibraryChoice.shownLibrary(capabilities: SourceCapabilities): Flow<ServerLibrary?> =
    state.map { it.shownLibrary(capabilities) }.distinctUntilChanged()
