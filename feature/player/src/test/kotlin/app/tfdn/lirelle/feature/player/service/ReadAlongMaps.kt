package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.OverlayItem
import app.tfdn.lirelle.core.library.model.OverlayResource
import app.tfdn.lirelle.core.library.model.OverlaySection
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import app.tfdn.lirelle.core.library.model.SpineEntry
import app.tfdn.lirelle.core.testing.ModelFixtures

/**
 * Read-along maps for the service's tests: books recorded on a server (anonymised), as the app's models
 * ([ModelFixtures]), and a small invented one.
 */
object ReadAlongMaps {

    /** The recorded read-along book [name] ("m4b", "mp3", "gap"…): the playlist and the spine. */
    fun recorded(name: String): Pair<ReadAlongPlaylist, List<SpineEntry>> =
        ModelFixtures.playlist(name) to ModelFixtures.spine(name)

    fun map(name: String, contentVersion: String? = "v1"): ReadAlongMap {
        val (playlist, spine) = recorded(name)
        return ReadAlongMap(playlist.bookId, playlist.fileId, contentVersion, playlist, spine)
    }

    /**
     * Book 42, read-along file 1: section 1 ("Opening") in `a.mp3` from 0 to 15 s (sentences at 0, 4 and 9 s),
     * section 2 without narration ([skippedBytes] in the spine), section 3 (no label) in `b.mp3` from 2 to 12 s
     * of that file (sentences at 15 and 19 s of the narration). 25 s in all.
     */
    fun invented(skippedBytes: Long = 10_000): Pair<ReadAlongPlaylist, List<SpineEntry>> {
        val items = listOf(
            OverlayItem(1, "text/one.xhtml", "s1", "audio/a.mp3", 0, 4_000),
            OverlayItem(1, "text/one.xhtml", "s2", "audio/a.mp3", 4_000, 9_000),
            OverlayItem(1, "text/one.xhtml", "s3", "audio/a.mp3", 9_000, 15_000),
            OverlayItem(3, "text/three.xhtml", "s4", "audio/b.mp3", 2_000, 6_000),
            OverlayItem(3, "text/three.xhtml", "s5", "audio/b.mp3", 6_000, 12_000),
        )
        val playlist = ReadAlongPlaylist(
            bookId = BookId("42"),
            fileId = FileId("1"),
            durationMs = 25_000,
            items = items,
            sections = listOf(
                OverlaySection(1, "text/one.xhtml", "Opening"),
                OverlaySection(3, "text/three.xhtml", null),
            ),
            resources = listOf(
                OverlayResource("audio/a.mp3", "audio/mpeg", 300_000),
                OverlayResource("audio/b.mp3", "audio/mpeg", 200_000),
            ),
        )
        val spine = listOf(
            SpineEntry(0, "text/cover.xhtml", 1_000),
            SpineEntry(1, "text/one.xhtml", 20_000),
            SpineEntry(2, "text/two.xhtml", skippedBytes),
            SpineEntry(3, "text/three.xhtml", 20_000),
        )
        return playlist to spine
    }

    fun inventedMap(skippedBytes: Long = 10_000, contentVersion: String? = "v1"): ReadAlongMap {
        val (playlist, spine) = invented(skippedBytes)
        return ReadAlongMap(BookId("42"), FileId("1"), contentVersion, playlist, spine)
    }
}
