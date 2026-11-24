package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.testing.ModelFixtures

/** The read-along books recorded on a server (anonymised), as the app's models ([ModelFixtures]). */
internal object ReadAlongFixtures {

    fun playlist(name: String): ReadAlongPlaylist = ModelFixtures.playlist(name)

    fun spine(name: String): List<SpineEntry> = ModelFixtures.spine(name)

    fun map(name: String, contentVersion: String? = "v1"): ReadAlongMap {
        val playlist = playlist(name)
        return ReadAlongMap(playlist.bookId, playlist.fileId, contentVersion, playlist, spine(name))
    }
}
