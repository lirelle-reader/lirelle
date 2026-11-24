package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.testing.ModelFixtures

/**
 * Books recorded on a server (anonymised), as the app's models ([ModelFixtures]), so that a plan is made from real
 * books. The name names the fixture.
 */
internal object Recorded {

    /** `mp3` (book 48: forty files) or `m4b` (one file). */
    fun manifest(name: String): AudioManifest = ModelFixtures.manifest(name)

    fun playlist(name: String): ReadAlongPlaylist = ModelFixtures.playlist(name)

    fun spine(name: String): List<SpineEntry> = ModelFixtures.spine(name)
}
