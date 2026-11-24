package app.tfdn.lirelle.feature.player.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The mark of a last book kept, in a folder of the test's own, as a profile has its own. */
fun newLastBookMark(): LastBookMark =
    LastBookMark(File(Files.createTempDirectory("profile").toFile().apply { deleteOnExit() }, "last_book.kept"))

/** A last-book store in memory, whose mark is touched where the caller runs. */
fun lastBookStore(
    file: MemoryPreferences = MemoryPreferences(),
    mark: LastBookMark = newLastBookMark(),
) = DataStoreLastBookStore(file, mark, Dispatchers.Unconfined)

/**
 * Preferences kept in memory, read and written where the caller runs: a test whose clock is its own waits
 * for no other thread.
 */
class MemoryPreferences : DataStore<Preferences> {
    private val stored = MutableStateFlow(emptyPreferences())

    /** The file cannot be read. */
    var broken = false

    override val data: Flow<Preferences> = stored.map { if (broken) throw IOException("unreadable") else it }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(stored.value).also { stored.value = it }
}
