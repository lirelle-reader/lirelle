package app.tfdn.lirelle.feature.player.service

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.testing.ModelFixtures

/** Audiobooks recorded on a server (anonymised), as the app's models ([ModelFixtures]); and an invented one. */
object Manifests {
    val m4b: AudioManifest by lazy { ModelFixtures.manifest("m4b") }
    val mp3: AudioManifest by lazy { ModelFixtures.manifest("mp3") }

    /** A small book of two files: chapters 0–60 s and 60–100 s in the first, 100–160 s the second. */
    fun twoFiles(chapters: Boolean = true) = AudioManifest(
        bookId = BookId("42"),
        contentVersion = "rev-1",
        title = "The Long Road",
        authors = listOf("Anne Verlaine"),
        narrators = listOf("Thomas Rivière"),
        hasCover = true,
        assets = listOf(
            AudioAsset("a1", FileId("101"), 0, "mp3", 100_000, 1_000, "e1"),
            AudioAsset("a2", FileId("102"), 1, "mp3", 60_000, 1_000, null),
        ),
        chapters = if (!chapters) emptyList() else listOf(
            AudioChapter("c1", "Opening", "a1", 0, 0, 60_000, 0),
            AudioChapter("c2", "Middle", "a1", 1, 60_000, 100_000, 60_000),
            AudioChapter("c3", "Closing", "a2", 2, 100_000, 160_000, 0),
        ),
        totalDurationMs = 160_000,
    )
}
