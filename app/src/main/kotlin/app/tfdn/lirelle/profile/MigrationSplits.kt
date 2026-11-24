package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.audio.settings.moveBookSpeeds
import app.tfdn.lirelle.core.comics.ComicsFiles
import app.tfdn.lirelle.core.comics.settings.moveReadingDirections
import app.tfdn.lirelle.core.library.LibraryFiles
import app.tfdn.lirelle.core.library.moveRecentSearches
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.feature.player.service.LastBookFiles
import app.tfdn.lirelle.feature.player.service.LastBookMark
import app.tfdn.lirelle.feature.player.service.moveLastBook
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * Opens this preferences file for [block] alone, and lets go of it before returning: a file may be open once
 * in a process, and the application's own stores open the same files after the migration. A file that cannot
 * be parsed is replaced, as those stores do. Nothing is made of a file that is only read.
 */
internal suspend fun <T> File.opened(block: suspend (DataStore<Preferences>) -> T): T {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
        ) { this }
        return block(store)
    } finally {
        scope.coroutineContext.job.cancelAndJoin()
    }
}

/**
 * A preferences file of the device that held, beside the device's own settings, something of the account: that
 * part goes to a file of the profile's, and the rest stays where it is.
 *
 * @property device the name of the device's file.
 * @property profile the name of the profile's file.
 * @property move what the module that owns both does to move its part; with no profile's file, to remove it.
 */
internal class Split(
    val device: String,
    val profile: String,
    val move: suspend (device: DataStore<Preferences>, profile: DataStore<Preferences>?) -> Unit,
)

internal val Splits = listOf(
    Split(LibraryFiles.Views, LibraryFiles.Searches, ::moveRecentSearches),
    Split(ComicsFiles.Settings, ComicsFiles.Directions, ::moveReadingDirections),
    Split(ListeningFiles.PlayerSettings, ListeningFiles.BookSpeeds, ::moveBookSpeeds),
)

/**
 * Takes the account's part out of the device's file.
 *
 * @param folders the profile it goes to; null when it is nobody's any more, and is only removed.
 */
internal suspend fun Split.run(context: Context, folders: ProfileFolders?) {
    val old = context.preferencesDataStoreFile(device)
    if (!old.exists()) return
    old.opened { settings ->
        if (folders == null) move(settings, null) else folders.dataStore(profile).opened { move(settings, it) }
    }
}

/**
 * The last book of the player, which the old installation kept in shared preferences of the application: it
 * is written in the profile's own file, with its mark. The old preferences are left for [deleteOldLastBook],
 * so that a start stopped anywhere in here finds them again and writes the same.
 *
 * @param folders the profile it goes to, when it is [account]'s.
 */
internal suspend fun moveOldLastBook(context: Context, folders: ProfileFolders, account: StoredOwner) {
    val old = context.getSharedPreferences(LastBookFiles.OldPreferences, Context.MODE_PRIVATE)
    val mark = LastBookMark(File(folders.files, LastBookFiles.Mark))
    folders.dataStore(LastBookFiles.Store).opened {
        moveLastBook(old, it, mark, account.serverUrl, account.userId)
    }
}

/** The old last book, once it is in its profile or is nobody's any more: it named a book and a server. */
internal fun deleteOldLastBook(context: Context) {
    context.deleteSharedPreferences(LastBookFiles.OldPreferences)
}
