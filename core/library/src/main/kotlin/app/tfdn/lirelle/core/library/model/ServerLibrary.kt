package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.LibraryId

/**
 * One library of a server, among those its account can open. Its [name] is what the user reads, and what a
 * BookOrbit server narrows its books by.
 */
data class ServerLibrary(val id: LibraryId, val name: String)
