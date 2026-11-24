package app.tfdn.lirelle.core.audio.narration

import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@OptIn(UnstableApi::class)
class ReadAlongManifestTest {

    private val m4b = ReadAlongFixtures.map("m4b", contentVersion = "v1")
    private val mp3 = ReadAlongFixtures.map("mp3", contentVersion = null)

    private val m4bManifest = m4b.toManifest("A Title", listOf("An Author"), listOf("A Narrator"), hasCover = true)
    private val mp3Manifest = mp3.toManifest("Another", emptyList(), emptyList(), hasCover = false)

    @Test
    fun `the manifest is the book's, with what the caller gives`() {
        assertThat(m4bManifest.bookId).isEqualTo(BookId("5"))
        assertThat(m4bManifest.title).isEqualTo("A Title")
        assertThat(m4bManifest.authors).containsExactly("An Author")
        assertThat(m4bManifest.narrators).containsExactly("A Narrator")
        assertThat(m4bManifest.hasCover).isTrue()
        assertThat(m4bManifest.contentVersion).isEqualTo("6:v1")
        assertThat(m4bManifest.totalDurationMs).isEqualTo(701_820)
    }

    @Test
    fun `the assets are the played segments of the read-along file, in playing order`() {
        assertThat(m4bManifest.assets).containsExactly(
            AudioAsset("OEBPS/Audio/track007.mp4#1", FileId("6"), 0, "mp4", 479_400, 16_114_705, "6:v1"),
            AudioAsset("OEBPS/Audio/track008.mp4#1", FileId("6"), 1, "mp4", 222_420, 3_534_494, "6:v1"),
        ).inOrder()
    }

    @Test
    fun `every mp3 segment is an asset of the read-along file, and without a content version the etag is the file`() {
        assertThat(mp3Manifest.assets).hasSize(12)
        assertThat(mp3Manifest.assets.first().assetId).isEqualTo("OEBPS/Audio/track009.mp3#1")
        assertThat(mp3Manifest.assets.first().durationMs).isEqualTo(46_920)
        assertThat(mp3Manifest.assets.last().assetId).isEqualTo("OEBPS/Audio/track009.mp3#2")
        assertThat(mp3Manifest.assets.last().durationMs).isEqualTo(76_340)
        assertThat(mp3Manifest.assets.map { it.assetId }).containsNoDuplicates()
        assertThat(mp3Manifest.assets.map { it.fileId }.toSet()).containsExactly(FileId("8"))
        assertThat(mp3Manifest.assets.map { it.sequence }).containsExactlyElementsIn(0..11).inOrder()
        assertThat(mp3Manifest.assets.map { it.format }.toSet()).containsExactly("mp3")
        assertThat(mp3Manifest.assets.map { it.etag }.toSet()).containsExactly("8:")
        assertThat(mp3Manifest.totalDurationMs).isEqualTo(3_100_500)
    }

    @Test
    fun `the chapters are the narrated sections on the map's timeline, titled by their label or not at all`() {
        assertThat(m4bManifest.chapters).containsExactly(
            AudioChapter("2", "", "OEBPS/Audio/track007.mp4#1", 0, 0, 444_660, 0),
            AudioChapter("3", "Chapter 7", "OEBPS/Audio/track007.mp4#1", 1, 444_660, 479_400, 444_660),
            AudioChapter("49", "Chapter 8", "OEBPS/Audio/track008.mp4#1", 2, 479_400, 701_820, 0),
        ).inOrder()
        // The offsets are inside the clipped segments: section 78 starts at the start of its segment of track 015.
        assertThat(mp3Manifest.chapters).containsExactly(
            AudioChapter("1", "", "OEBPS/Audio/track009.mp3#1", 0, 0, 46_920, 0),
            AudioChapter("6", "Chapter 9", "OEBPS/Audio/track010.mp3#1", 1, 46_920, 1_550_280, 0),
            AudioChapter("78", "Chapter 10", "OEBPS/Audio/track015.mp3#1", 2, 1_550_280, 3_100_500, 0),
        ).inOrder()
    }

    @Test
    fun `each asset's segment is found by its id, and is the track at its place`() {
        mp3Manifest.assets.forEachIndexed { index, asset ->
            assertThat(mp3.trackOf(asset.assetId)).isEqualTo(mp3.tracks[index])
        }
        val last = mp3.trackOf("OEBPS/Audio/track009.mp3#2")!!
        assertThat(last.href).isEqualTo("OEBPS/Audio/track009.mp3")
        assertThat(last.clipStartMs to last.clipEndMs).isEqualTo(0L to 76_340L)
        assertThat(mp3.trackOf("OEBPS/Audio/track009.mp3")).isNull()
        assertThat(mp3.trackOf("OEBPS/Audio/track009.mp3#3")).isNull()
    }

    @Test
    fun `the 3a timeline over the manifest maps the map's times to the segments and back`() {
        val timeline = BookTimeline(mp3Manifest)
        val track011 = mp3Manifest.assets[2]

        assertThat(timeline.totalMs).isEqualTo(mp3.totalMs)
        assertThat(timeline.assetPositionOf(349_140)).isEqualTo(track011 to 1_660L)
        assertThat(timeline.bookTimeOf("OEBPS/Audio/track011.mp3#1", 1_660)).isEqualTo(349_140)
        assertThat(timeline.chapterAt(1_550_280)).isEqualTo(2)
        assertThat(mp3.sentenceAt(timeline.bookTimeOf("OEBPS/Audio/track011.mp3#1", 0)!!)!!.fragment)
            .isEqualTo("p010-s68")
        assertThat(timeline.assetPositionOf(mp3.totalMs)).isEqualTo(mp3Manifest.assets.last() to 76_340L)
        assertThat(timeline.bookTimeOf("OEBPS/Audio/track009.mp3#2", 0)).isEqualTo(3_024_160)
    }

    @Test
    fun `a run of clips without length makes no asset, so the 3a timeline knows every duration`() {
        val manifest = synthetic(items = clipsWithoutEnds).toManifest("", emptyList(), emptyList(), hasCover = false)
        val timeline = BookTimeline(manifest)

        assertThat(manifest.assets.map { it.assetId to it.durationMs }).containsExactly("a.mp3#1" to 8_000L)
        assertThat(timeline.durationsKnown).isTrue()
        assertThat(timeline.totalMs).isEqualTo(8_000)
        assertThat(timeline.assetPositionOf(8_000)).isEqualTo(manifest.assets.single() to 8_000L)
    }

    @Test
    fun `two files, or two versions of a file, never share audio cache entries`() {
        // Both files have a track named OEBPS/Audio/track001.mp4.
        val gap = ReadAlongFixtures.map("gap").toManifest("", emptyList(), emptyList(), hasCover = false)
        val after = ReadAlongFixtures.map("after").toManifest("", emptyList(), emptyList(), hasCover = false)
        val newer = ReadAlongFixtures.map("gap", "v2").toManifest("", emptyList(), emptyList(), hasCover = false)
        fun AudioManifest.firstKey() = assets.first().let { AudioCache.key(it.assetId, it.etag) }

        assertThat(gap.assets.first().assetId).isEqualTo(after.assets.first().assetId)
        assertThat(gap.firstKey()).isNotEqualTo(after.firstKey())
        assertThat(gap.firstKey()).isNotEqualTo(newer.firstKey())
    }
}
