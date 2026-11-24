package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.PlannedFile
import java.io.File

/** A cache of the app that may already hold a file a download needs: the reader's, the player's. */
fun interface CachedFiles {
    /**
     * Copies [file] to [into] when the cache has it whole, in the version the plan names. False when it has
     * not, or when the copy could not be written: the file is then fetched. Called off the main thread.
     */
    fun copyOf(file: PlannedFile, into: File): Boolean
}
