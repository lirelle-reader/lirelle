package app.tfdn.lirelle.feature.player.service

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import android.net.Uri
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.audio.narration.toManifest
import app.tfdn.lirelle.core.audio.narration.trackOf
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookPlaylistTest {

    private val cover = "https://books.example/api/v1/books/1/cover?v=3"
    private val urls = { bookId: BookId, assetId: String ->
        "https://books.example/api/v1/audiobooks/$bookId/assets/$assetId/content"
    }

    private fun playlistOf(manifest: app.tfdn.lirelle.core.library.model.AudioManifest) =
        BookPlaylist.of(manifest, cover, { "Part $it" }) { urls(manifest.bookId, it) }!!

    @Test
    fun `an m4b is one item streamed from its asset route, cached by asset and etag`() {
        val playlist = playlistOf(Manifests.m4b)

        assertThat(playlist.items).hasSize(1)
        val item = playlist.items.single()
        val asset = Manifests.m4b.assets.single()
        assertThat(item.mediaId).isEqualTo(asset.assetId)
        assertThat(item.localConfiguration!!.uri).isEqualTo(Uri.parse(urls(BookId("1"), asset.assetId)))
        assertThat(item.localConfiguration!!.customCacheKey).isEqualTo(AudioCache.key(asset.assetId, asset.etag))
        assertThat(item.mediaMetadata.title.toString()).isEqualTo("Book 1")
        assertThat(item.mediaMetadata.artist.toString()).isEqualTo(Manifests.m4b.chapters.first().title)
        assertThat(item.mediaMetadata.artworkUri).isEqualTo(Uri.parse(cover))
        assertThat(playlist.chapters).hasSize(Manifests.m4b.chapters.size)
    }

    @Test
    fun `an mp3 book is one item per file in order, its file-name chapters shown as parts`() {
        val playlist = playlistOf(Manifests.mp3)

        val assets = Manifests.mp3.assets.sortedBy { it.sequence }
        assertThat(playlist.items.map { it.mediaId }).isEqualTo(assets.map { it.assetId })
        assertThat(playlist.items.map { it.localConfiguration!!.customCacheKey })
            .isEqualTo(assets.map { AudioCache.key(it.assetId, it.etag) })
        assertThat(playlist.chapters).hasSize(40)
        assertThat(playlist.chapters[1].startMs).isEqualTo(3_382_000)
        assertThat(playlist.chapters[1].endMs).isEqualTo(5_127_000)
    }

    @Test
    fun `book time maps to the file and the position inside it, and back`() {
        val playlist = playlistOf(Manifests.twoFiles())

        assertThat(playlist.itemPositionOf(70_000)).isEqualTo(ItemPosition(0, 70_000))
        assertThat(playlist.itemPositionOf(130_000)).isEqualTo(ItemPosition(1, 30_000))
        assertThat(playlist.bookTimeOf(1, 30_000)).isEqualTo(130_000)
        assertThat(playlist.bookTimeOf(0, 5_000)).isEqualTo(5_000)
        assertThat(playlist.bookTimeOf(5, 0)).isNull()
    }

    @Test
    fun `chapters carry their display titles, bounds and manifest ids, and the chapter of a book time is found`() {
        val playlist = playlistOf(Manifests.twoFiles())

        assertThat(playlist.chapters).containsExactly(
            PlaylistChapter("Opening", 0, 60_000, chapterId = "c1"),
            PlaylistChapter("Middle", 60_000, 100_000, chapterId = "c2"),
            PlaylistChapter("Closing", 100_000, 160_000, chapterId = "c3"),
        ).inOrder()
        assertThat(playlist.chapterIndexAt(0)).isEqualTo(0)
        assertThat(playlist.chapterIndexAt(99_999)).isEqualTo(1)
        assertThat(playlist.chapterIndexAt(100_000)).isEqualTo(2)
        assertThat(playlist.items[1].mediaMetadata.artist.toString()).isEqualTo("Closing")
    }

    @Test
    fun `a book without chapters is one chapter for the whole book`() {
        val playlist = playlistOf(Manifests.twoFiles(chapters = false))

        assertThat(playlist.chapters).containsExactly(PlaylistChapter("The Long Road", 0, 160_000))
        assertThat(playlist.chapterIndexAt(150_000)).isEqualTo(0)
    }

    @Test
    fun `a book whose chapters all lack a title is in parts, named so`() {
        val book = Manifests.twoFiles()
        val playlist = playlistOf(book.copy(chapters = book.chapters.map { it.copy(title = " ") }))

        assertThat(playlist.inParts).isTrue()
        assertThat(playlist.chapters.map { it.title }).containsExactly("Part 1", "Part 2", "Part 3").inOrder()
    }

    @Test
    fun `files named alike are parts, as the recorded mp3 book's`() {
        assertThat(playlistOf(Manifests.mp3).inParts).isTrue()
        assertThat(playlistOf(Manifests.mp3).chapters.first().title).isEqualTo("Part 1")
    }

    @Test
    fun `a book with one titled chapter is in chapters, a book without chapters is not in parts`() {
        val book = Manifests.twoFiles()
        val oneTitled = book.copy(
            chapters = book.chapters.map { if (it.id == "c2") it else it.copy(title = "") },
        )

        assertThat(playlistOf(oneTitled).inParts).isFalse()
        assertThat(playlistOf(oneTitled).chapters.map { it.title }).containsExactly("", "Middle", "").inOrder()
        assertThat(playlistOf(book).inParts).isFalse()
        assertThat(playlistOf(Manifests.m4b).inParts).isFalse()
        assertThat(playlistOf(Manifests.twoFiles(chapters = false)).inParts).isFalse()
    }

    @Test
    fun `without an address for its files there is no playlist`() {
        assertThat(BookPlaylist.of(Manifests.twoFiles(), cover, { "Part $it" }) { null }).isNull()
        assertThat(BookPlaylist.of(Manifests.twoFiles().copy(assets = emptyList()), cover, { "" }) { "x" }).isNull()
    }

    @Test
    fun `a book without chapters whose files' lengths are unknown has a chapter of unknown length`() {
        val unknown = Manifests.twoFiles(chapters = false).let { m ->
            m.copy(assets = m.assets.map { it.copy(durationMs = null) })
        }

        val playlist = playlistOf(unknown)

        assertThat(playlist.chapters.single().endMs).isNull()
        assertThat(playlist.chapters.single().durationMs).isNull()
    }

    @Test
    fun `the end of a chapter that ends its file is reached at the start of the next, the last at its end`() {
        val playlist = playlistOf(Manifests.twoFiles())

        assertThat(playlist.endPlaceOf(100_000)).isEqualTo(ItemPosition(1, 1))
        assertThat(playlist.endPlaceOf(160_000)).isEqualTo(ItemPosition(1, C.TIME_END_OF_SOURCE))
        assertThat(playlist.endPlaceOf(60_000)).isEqualTo(ItemPosition(0, 59_999))
    }

    private fun segmentsOf(map: ReadAlongMap) = BookPlaylist.ofSegments(
        manifest = map.toManifest("Narrated", emptyList(), emptyList(), hasCover = true),
        coverUrl = cover,
        clip = { asset -> map.trackOf(asset.assetId)?.let { ClipRange(it.clipStartMs, it.clipEndMs) } },
    ) { assetId -> map.trackOf(assetId)?.let { "https://books.example/overlay/${it.href}" } }!!

    @Test
    fun `a narration's segments are items clipped to their part of the file, two parts of one file apart`() {
        val map = ReadAlongMaps.map("mp3", contentVersion = null)

        val playlist = segmentsOf(map)

        assertThat(playlist.items).hasSize(map.tracks.size)
        val first = playlist.items.first()
        val last = playlist.items.last()
        // The same file twice: two items, each clipped to its own segment.
        assertThat(first.localConfiguration!!.uri).isEqualTo(last.localConfiguration!!.uri)
        assertThat(first.localConfiguration!!.uri.toString())
            .isEqualTo("https://books.example/overlay/OEBPS/Audio/track009.mp3")
        assertThat(first.clippingConfiguration.startPositionMs).isEqualTo(map.tracks.first().clipStartMs)
        assertThat(first.clippingConfiguration.endPositionMs).isEqualTo(map.tracks.first().clipEndMs)
        assertThat(last.clippingConfiguration.startPositionMs).isEqualTo(0)
        assertThat(last.clippingConfiguration.endPositionMs).isEqualTo(76_340)
        // Each segment its own cache entries, keyed by the read-along file and its version.
        val keys = playlist.items.map { it.localConfiguration!!.customCacheKey }
        assertThat(keys).containsNoDuplicates()
        assertThat(keys.first()).isEqualTo(AudioCache.key("OEBPS/Audio/track009.mp3#1", "8:"))
    }

    @Test
    fun `a narration's positions are inside the segments, and its chapters keep their section titles`() {
        val map = ReadAlongMaps.map("m4b")

        val playlist = segmentsOf(map)

        assertThat(playlist.items).hasSize(2)
        assertThat(playlist.items[1].clippingConfiguration.startPositionMs).isEqualTo(map.tracks[1].clipStartMs)
        assertThat(playlist.itemPositionOf(479_400 + 1_000)).isEqualTo(ItemPosition(1, 1_000))
        assertThat(playlist.chapters.map { it.title }).containsExactly("", "Chapter 7", "Chapter 8").inOrder()
        assertThat(playlist.artworkUrl).isEqualTo(cover)
    }

    @Test
    fun `a narration's chapters tell their section's place in the book, an audiobook's none`() {
        val narration = segmentsOf(ReadAlongMaps.inventedMap())
        val audiobook = playlistOf(Manifests.twoFiles())

        assertThat(narration.chapters.map { it.sectionIndex }).containsExactly(1, 3).inOrder()
        assertThat(audiobook.chapters.map { it.sectionIndex }.toSet()).containsExactly(null)
    }

    @Test
    fun `a narration's untitled section is named by its place in the book, as the app's screens name it`() {
        val playlist = segmentsOf(ReadAlongMaps.inventedMap())
        val texts = NarrationTexts(ApplicationProvider.getApplicationContext<Context>().resources)

        assertThat(playlist.narrationChapterTitle(0, texts)).isEqualTo("Opening")
        assertThat(playlist.narrationChapterTitle(1, texts)).isEqualTo("Section 4")
        assertThat(playlist.narrationChapterTitle(2, texts)).isNull()
        // Untitled sections are still sections: only an audiobook is in parts.
        assertThat(playlist.inParts).isFalse()
        // Its place among the narrated sections only when its place in the book is not known.
        assertThat(sectionTitle("", sectionIndex = null, position = 1) { "Section $it" }).isEqualTo("Section 2")
    }

    @Test
    fun `an audiobook's items are not clipped`() {
        val playlist = playlistOf(Manifests.twoFiles())

        assertThat(playlist.items.map { it.clippingConfiguration }.toSet())
            .containsExactly(MediaItem.ClippingConfiguration.UNSET)
    }
}
