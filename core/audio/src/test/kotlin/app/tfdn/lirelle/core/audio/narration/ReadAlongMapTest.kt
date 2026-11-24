package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap.NarratedSection
import app.tfdn.lirelle.core.audio.narration.ReadAlongMap.Track
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.OverlayItem
import app.tfdn.lirelle.core.library.model.OverlayResource
import app.tfdn.lirelle.core.library.model.OverlaySection
import app.tfdn.lirelle.core.library.model.ReadAlongPlaylist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadAlongMapTest {

    // An m4b cut into tracks: two tracks used out of four resources; sections 2 (no label), 3 and 49.
    private val m4b = ReadAlongFixtures.map("m4b")

    // A narrated chapter missing in the middle: sections 4, 6 and 24; 5 has no narration.
    private val gap = ReadAlongFixtures.map("gap")

    // One track per mp3; sentences over two tracks.
    private val mp3 = ReadAlongFixtures.map("mp3")

    private fun ReadAlongMap.Sentence?.id() = this?.let { "${it.sectionIndex}#${it.fragment}" }

    @Test
    fun `the map keeps the book, the file and the content version`() {
        assertThat(m4b.bookId).isEqualTo(BookId("5"))
        assertThat(m4b.fileId).isEqualTo(FileId("6"))
        assertThat(m4b.contentVersion).isEqualTo("v1")
    }

    @Test
    fun `the tracks are the played segments, one after the other, from first clip begin to last clip end`() {
        assertThat(m4b.tracks).containsExactly(
            Track("OEBPS/Audio/track007.mp4", "audio/mp4", 16_114_705, 0, 479_400, 0, 479_400),
            Track("OEBPS/Audio/track008.mp4", "audio/mp4", 3_534_494, 0, 222_420, 479_400, 222_420),
        ).inOrder()
        assertThat(m4b.totalMs).isEqualTo(701_820)
    }

    @Test
    fun `the playing order is the reading order, not the resources'`() {
        val map = synthetic(
            items = listOf(item(1, "s1", "a.mp3", 0, 4_000), item(1, "s2", "b.mp3", 0, 3_000)),
            resources = listOf(resource("c.mp3"), resource("b.mp3"), resource("a.mp3")),
        )

        assertThat(map.tracks.map { it.href }).containsExactly("a.mp3", "b.mp3").inOrder()
        assertThat(map.tracks.map { it.startMs }).containsExactly(0L, 4_000L).inOrder()
    }

    @Test
    fun `a file read in two places is played twice, so the narration ends at the book's real end`() {
        // Track 009 holds section 1 from 76.34 s, and the end of section 78 before that.
        assertThat(mp3.tracks.first()).isEqualTo(
            Track("OEBPS/Audio/track009.mp3", "audio/mpeg", 1_969_096, 76_340, 123_260, 0, 46_920),
        )
        assertThat(mp3.tracks.last()).isEqualTo(
            Track("OEBPS/Audio/track009.mp3", "audio/mpeg", 1_969_096, 0, 76_340, 3_024_160, 76_340),
        )
        assertThat(mp3.tracks).hasSize(12)
        assertThat(mp3.totalMs).isEqualTo(3_100_500)
        val last = mp3.sentenceAt(mp3.totalMs)!!
        assertThat(last.id()).isEqualTo("78#p011-s262")
        assertThat(last.endMs).isEqualTo(mp3.totalMs)
        assertThat(mp3.isLastSentence(last)).isTrue()
        assertThat(mp3.sentenceAt(0).id()).isEqualTo("1#p009-s0")
    }

    @Test
    fun `a clip starting late in its file starts its segment there`() {
        // Section 24's only clip starts 4 268.92 s into track 006; it plays right after section 6.
        assertThat(gap.tracks.last().clipStartMs).isEqualTo(4_268_920)
        assertThat(gap.tracks.last().startMs).isEqualTo(3_445_880)
        assertThat(gap.sentenceAt(3_445_879).id()).isEqualTo("6#p005-s418")
        assertThat(gap.sentenceAt(3_445_880).id()).isEqualTo("24#p006-s0")
        assertThat(gap.totalMs).isEqualTo(3_764_940)
    }

    @Test
    fun `a jump back, or far forward, in a file starts a new segment, a short pause does not`() {
        val map = synthetic(
            items = listOf(
                item(1, "s1", "a.mp3", 0, 4_000),
                item(1, "s2", "a.mp3", 5_000, 8_000),
                item(1, "s3", "a.mp3", 8_000 + SegmentForwardToleranceMs + 1, 40_000),
                item(1, "s4", "a.mp3", 1_000, 2_000),
                item(1, "s5", "a.mp3", 2_000 - SegmentBackToleranceMs, 3_000),
            ),
        )

        assertThat(map.tracks.map { it.clipStartMs to it.clipEndMs })
            .containsExactly(0L to 8_000L, 38_001L to 40_000L, 1_000L to 3_000L).inOrder()
        assertThat(map.tracks.map { it.startMs }).containsExactly(0L, 8_000L, 9_999L).inOrder()
        assertThat(map.totalMs).isEqualTo(11_999)
    }

    @Test
    fun `between two clips of a segment the sentence heard before stays`() {
        val map = synthetic(items = listOf(item(1, "s1", "a.mp3", 0, 4_000), item(1, "s2", "a.mp3", 5_000, 8_000)))

        assertThat(map.sentenceAt(4_500).id()).isEqualTo("1#s1")
        assertThat(map.sentenceAt(5_000).id()).isEqualTo("1#s2")
    }

    @Test
    fun `a clip without an end runs to the next clip of its file, else it has no length and makes no segment`() {
        val map = synthetic(items = clipsWithoutEnds)

        assertThat(map.tracks.map { it.href to it.durationMs }).containsExactly("a.mp3" to 8_000L)
        assertThat(map.totalMs).isEqualTo(8_000)
        assertThat(map.sentenceAt(4_999).id()).isEqualTo("1#s1")
        assertThat(map.sentenceAt(5_000).id()).isEqualTo("1#s2")
        // The empty last run sits at the very end.
        val last = map.sentenceOf(1, "s3")!!
        assertThat(last.startMs to last.endMs).isEqualTo(8_000L to 8_000L)
        assertThat(map.sentenceAt(8_000)).isEqualTo(last)
    }

    @Test
    fun `an empty run between two segments takes the next segment's start`() {
        val map = synthetic(
            items = listOf(
                item(1, "s1", "a.mp3", 0, 4_000),
                item(1, "s2", "b.mp3", 1_000, 1_000),
                item(1, "s3", "c.mp3", 0, 3_000),
            ),
        )

        assertThat(map.tracks.map { it.href to it.startMs }).containsExactly("a.mp3" to 0L, "c.mp3" to 4_000L).inOrder()
        assertThat(map.sentenceOf(1, "s2")!!.startMs).isEqualTo(4_000)
        assertThat(map.sentenceAt(4_000).id()).isEqualTo("1#s3")
        assertThat(map.clockSeconds(5_000)).isWithin(1e-9).of(5.0)
        assertThat(map.totalMs).isEqualTo(7_000)
    }

    @Test
    fun `a clip never ends after its segment`() {
        // The second clip starts before the segment's first begin, within the tolerance, and runs past its end.
        val map = synthetic(items = listOf(item(1, "s1", "a.mp3", 5_000, 5_100), item(1, "s2", "a.mp3", 4_700, 5_000)))

        assertThat(map.totalMs).isEqualTo(100)
        assertThat(map.sentenceOf(1, "s2")!!.endMs).isEqualTo(100)
    }

    @Test
    fun `at a boundary, the sentence at a time is the one that starts there`() {
        assertThat(m4b.sentenceAt(444_659).id()).isEqualTo("2#p006-s0")
        assertThat(m4b.sentenceAt(444_660).id()).isEqualTo("3#p007-s0")
        assertThat(m4b.sentenceAt(450_000).id()).isEqualTo("3#p007-s0")
        assertThat(m4b.sentenceAt(479_400).id()).isEqualTo("49#p008-s0")
    }

    @Test
    fun `the sentence at the very end is the last one, beyond the map or before it there is none`() {
        assertThat(m4b.sentenceAt(701_820).id()).isEqualTo("49#p008-s66")
        assertThat(m4b.sentenceAt(701_821)).isNull()
        assertThat(m4b.sentenceAt(-1)).isNull()
    }

    @Test
    fun `consecutive items with the same fragment are one sentence`() {
        val sentence = gap.sentenceAt(24_300)!!

        assertThat(sentence.id()).isEqualTo("4#p004-s0")
        assertThat(sentence.startMs).isEqualTo(0)
        assertThat(sentence.endMs).isEqualTo(31_560)
        assertThat(sentence.textHref).isEqualTo("OEBPS/Text/section004.xhtml")
        assertThat(gap.sentenceAt(31_560).id()).isEqualTo("4#p004-s1")
    }

    @Test
    fun `a sentence over two tracks is one`() {
        // Track 011's segment starts at 347 480 ms.
        val sentence = mp3.sentenceAt(347_480)!!

        assertThat(sentence.id()).isEqualTo("6#p010-s68")
        assertThat(sentence.startMs).isEqualTo(341_520)
        assertThat(sentence.endMs).isEqualTo(349_140)
        assertThat(mp3.sentenceAt(341_520)).isEqualTo(sentence)
    }

    @Test
    fun `the ordinals follow the reading order`() {
        assertThat(m4b.sentenceAt(0)!!.ordinal).isEqualTo(0)
        assertThat(m4b.sentenceAt(444_660)!!.ordinal).isEqualTo(1)
        assertThat(m4b.sentenceAt(701_820)!!.ordinal).isEqualTo(69)
        assertThat(mp3.sentenceAt(mp3.totalMs)!!.ordinal).isEqualTo(652)
    }

    @Test
    fun `previous goes to the previous sentence within the grace of the current one's start`() {
        assertThat(gap.previousSentence(31_560 + 1_999).id()).isEqualTo("4#p004-s0")
        assertThat(gap.previousSentence(31_560 + 2_000).id()).isEqualTo("4#p004-s1")
        assertThat(gap.previousSentence(31_560 + 500, graceMs = 400).id()).isEqualTo("4#p004-s1")
    }

    @Test
    fun `previous on the first sentence stays on it`() {
        assertThat(m4b.previousSentence(100).id()).isEqualTo("2#p006-s0")
    }

    @Test
    fun `next is the sentence after the current one, none after the last`() {
        assertThat(m4b.nextSentence(444_000).id()).isEqualTo("3#p007-s0")
        assertThat(m4b.nextSentence(444_660).id()).isEqualTo("3#p007-s1")
        assertThat(m4b.nextSentence(700_500)).isNull()
        assertThat(mp3.nextSentence(341_520 + 7_000).id()).isEqualTo("6#p010-s69")
        assertThat(mp3.nextSentence(mp3.totalMs - 1)).isNull()
    }

    @Test
    fun `the last sentence is known`() {
        assertThat(m4b.isLastSentence(m4b.sentenceAt(701_000)!!)).isTrue()
        assertThat(m4b.isLastSentence(m4b.sentenceAt(699_000)!!)).isFalse()
        assertThat(mp3.isLastSentence(mp3.sentenceOf(78, "p011-s253")!!)).isFalse()
    }

    @Test
    fun `a sentence is found by its section and fragment`() {
        val sentence = m4b.sentenceOf(49, "p008-s1")!!

        assertThat(sentence.startMs).isEqualTo(483_040)
        assertThat(m4b.sentenceAt(483_040)).isEqualTo(sentence)
        assertThat(m4b.sentenceOf(49, "nope")).isNull()
        assertThat(m4b.sentenceOf(3, "p008-s1")).isNull()
    }

    @Test
    fun `the first sentence from a section without narration is that of the next narrated one`() {
        assertThat(gap.firstSentenceFrom(4).id()).isEqualTo("4#p004-s0")
        assertThat(gap.firstSentenceFrom(5).id()).isEqualTo("6#p005-s0")
        assertThat(gap.firstSentenceFrom(0).id()).isEqualTo("4#p004-s0")
        assertThat(gap.firstSentenceFrom(25)).isNull()
    }

    @Test
    fun `a section's sentences play in reading order, across the segments it spans`() {
        // Section 78 starts 29.64 s into track 015 and ends at the start of track 009.
        assertThat(mp3.firstSentenceFrom(78)!!.startMs).isEqualTo(1_550_280)
        assertThat(mp3.sentenceOf(78, "p011-s254")!!.startMs).isEqualTo(3_024_160)
    }

    @Test
    fun `the fragments of a section are in reading order, without duplicates`() {
        val fragments = gap.fragmentsOf(4)

        assertThat(fragments.take(3)).containsExactly("p004-s0", "p004-s1", "p004-s2").inOrder()
        assertThat(fragments).hasSize(217)
        assertThat(fragments).containsNoDuplicates()
        assertThat(gap.fragmentsOf(5)).isEmpty()
        assertThat(mp3.fragmentsOf(78).last()).isEqualTo("p011-s262")
    }

    @Test
    fun `the narrated sections follow one another on the timeline, with their labels`() {
        assertThat(m4b.sections).containsExactly(
            NarratedSection(2, null, startMs = 0, endMs = 444_660),
            NarratedSection(3, "Chapter 7", startMs = 444_660, endMs = 479_400),
            NarratedSection(49, "Chapter 8", startMs = 479_400, endMs = 701_820),
        ).inOrder()
        assertThat(gap.sections.map { it.index }).containsExactly(4, 6, 24).inOrder()
        assertThat(gap.sections[1].endMs).isEqualTo(3_445_880)
        assertThat(mp3.sections.map { it.startMs to it.endMs })
            .containsExactly(0L to 46_920L, 46_920L to 1_550_280L, 1_550_280L to 3_100_500L).inOrder()
    }

    @Test
    fun `the sections skipped between two narrated ones are listed with their size`() {
        assertThat(gap.skippedBetween(4, 6))
            .containsExactly(ReadAlongMap.SkippedSection(5, "OEBPS/Text/section062.xhtml", 73_306))
        assertThat(gap.skippedBetween(6, 24).map { it.index }).containsExactlyElementsIn(7..23).inOrder()
        assertThat(gap.skippedBetween(4, 4)).isEmpty()
        assertThat(gap.skippedBetween(6, 4)).isEmpty()
    }

    @Test
    fun `the place at a time is the sentence's fragment, its section and the clock`() {
        assertThat(m4b.placeAt(450_000))
            .isEqualTo(NarrationPlace(450.0, "OEBPS/Text/section008.xhtml#p007-s0", 3))
        assertThat(m4b.placeAt(9_999_999))
            .isEqualTo(NarrationPlace(701.82, "OEBPS/Text/section009.xhtml#p008-s66", 49))
        assertThat(gap.placeAt(3_445_880 + 5_000))
            .isEqualTo(NarrationPlace(3_450.88, "OEBPS/Text/section006.xhtml#p006-s0", 24))
    }

    @Test
    fun `every recorded map builds`() {
        for (name in listOf("m4b", "mp3", "gap", "unclipped", "after")) {
            val map = ReadAlongFixtures.map(name)
            assertThat(map.sections).isNotEmpty()
            assertThat(map.sentenceAt(map.totalMs)).isNotNull()
        }
    }

    @Test
    fun `a map without items is empty`() {
        val map = synthetic(items = emptyList())

        assertThat(map.totalMs).isEqualTo(0)
        assertThat(map.tracks).isEmpty()
        assertThat(map.sentenceAt(0)).isNull()
        assertThat(map.placeAt(0)).isNull()
        assertThat(map.clockSeconds(0)).isEqualTo(0.0)
        assertThat(map.percentAt(0)).isEqualTo(0.0)
    }
}

internal fun item(section: Int, fragment: String?, audio: String, beginMs: Long, endMs: Long?) =
    OverlayItem(section, "text$section.xhtml", fragment, audio, beginMs, endMs)

internal fun resource(href: String) = OverlayResource(href, "audio/mpeg", 1_000)

/** The first runs to the second; the third, alone in its file, has no length. */
internal val clipsWithoutEnds = listOf(
    item(1, "s1", "a.mp3", 0, null),
    item(1, "s2", "a.mp3", 5_000, 8_000),
    item(1, "s3", "b.mp3", 1_000, null),
)

internal fun synthetic(items: List<OverlayItem>, resources: List<OverlayResource>? = null): ReadAlongMap {
    val used = items.map { it.audioHref }.distinct()
    val sections = items.map { it.sectionIndex }.distinct().map { OverlaySection(it, "text$it.xhtml", null) }
    val playlist = ReadAlongPlaylist(BookId("1"), FileId("2"), null, items, sections, resources ?: used.map(::resource))
    return ReadAlongMap(BookId("1"), FileId("2"), "v1", playlist, emptyList())
}
