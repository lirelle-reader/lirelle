package app.tfdn.lirelle.core.audio.timeline

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterTitlesTest {

    private val part: (Int) -> String = { "Part $it" }

    @Test
    fun `file names that differ only by a number become parts`() {
        val titles = (1..40).map { "The Harbour Ledger [fr] (1993)-Part%02d".format(it) }

        val shown = displayChapterTitles(oneFileEach(titles), part)

        assertThat(shown).hasSize(40)
        assertThat(shown.first()).isEqualTo("Part 1")
        assertThat(shown[8]).isEqualTo("Part 9")
        assertThat(shown.last()).isEqualTo("Part 40")
    }

    @Test
    fun `every number counts, not only the last one`() {
        val titles = listOf("Part 1 - The Harbour Ledger (1993)", "Part 2 - The Harbour Ledger (1993)")

        assertThat(displayChapterTitles(oneFileEach(titles), part)).containsExactly("Part 1", "Part 2").inOrder()
    }

    @Test
    fun `parts are numbered by position, not by the number in the title`() {
        val shown = displayChapterTitles(oneFileEach(listOf("Track 07", "Track 08", "Track 09")), part)

        assertThat(shown).containsExactly("Part 1", "Part 2", "Part 3").inOrder()
    }

    @Test
    fun `real chapters keep their titles, however alike`() {
        val titles = (1..12).map { "Chapitre $it" }

        assertThat(displayChapterTitles(oneFile(titles), part)).isEqualTo(titles)
    }

    @Test
    fun `as many chapters as files, but not at their starts, keep their titles`() {
        val titles = listOf("Track 1", "Track 2")
        val files = oneFileEach(titles)
        val shifted = files.copy(chapters = files.chapters.map { it.copy(assetOffsetMs = 5_000) })

        assertThat(displayChapterTitles(shifted, part)).isEqualTo(titles)
    }

    @Test
    fun `titles come in book order`() {
        val files = oneFileEach(listOf("The Harbour", "A Letter from Lyon"))

        val shown = displayChapterTitles(files.copy(chapters = files.chapters.reversed()), part)

        assertThat(shown).containsExactly("The Harbour", "A Letter from Lyon").inOrder()
    }

    @Test
    fun `titles with different words are kept`() {
        val titles = listOf("The Harbour", "A Letter from Lyon", "Chapter 3")

        assertThat(displayChapterTitles(oneFileEach(titles), part)).isEqualTo(titles)
    }

    @Test
    fun `numbered titles with words of their own are kept`() {
        val titles = listOf("1. The Harbour", "2. A Letter from Lyon")

        assertThat(displayChapterTitles(oneFileEach(titles), part)).isEqualTo(titles)
    }

    @Test
    fun `a single chapter keeps its title`() {
        assertThat(displayChapterTitles(oneFileEach(listOf("Part01")), part)).containsExactly("Part01")
    }

    @Test
    fun `numbers in different places keep the titles`() {
        val titles = listOf("01 - Lantern", "Lantern - 02")

        assertThat(displayChapterTitles(oneFileEach(titles), part)).isEqualTo(titles)
    }

    @Test
    fun `identical titles without a number are kept`() {
        val titles = listOf("Untitled", "Untitled")

        assertThat(displayChapterTitles(oneFileEach(titles), part)).isEqualTo(titles)
    }

    @Test
    fun `no chapters give no titles`() {
        assertThat(displayChapterTitles(oneFileEach(emptyList()), part)).isEmpty()
    }

    @Test
    fun `a book whose chapters all lack a title is in parts, and they are named so`() {
        val untitled = oneFile(listOf("", " ", ""))

        assertThat(chaptersAreParts(untitled)).isTrue()
        assertThat(displayChapterTitles(untitled, part)).containsExactly("Part 1", "Part 2", "Part 3").inOrder()
        assertThat(chaptersAreParts(oneFileEach(listOf("")))).isTrue()
    }

    @Test
    fun `one chapter with a title of its own makes a book in chapters`() {
        val titles = listOf("", "The Harbour", "")

        assertThat(chaptersAreParts(oneFile(titles))).isFalse()
        assertThat(displayChapterTitles(oneFile(titles), part)).isEqualTo(titles)
    }

    @Test
    fun `a book without chapters is not in parts`() {
        assertThat(chaptersAreParts(oneFileEach(emptyList()))).isFalse()
    }

    @Test
    fun `files named alike are parts, real chapters and titles with words of their own are not`() {
        assertThat(chaptersAreParts(oneFileEach(listOf("Track 07", "Track 08")))).isTrue()
        assertThat(chaptersAreParts(oneFile(listOf("Chapitre 1", "Chapitre 2")))).isFalse()
        assertThat(chaptersAreParts(oneFileEach(listOf("1. The Harbour", "2. A Letter from Lyon")))).isFalse()
    }

    /** One file per chapter, each chapter the whole of its file. */
    private fun oneFileEach(titles: List<String>): AudioManifest {
        val assets = titles.indices.map { AudioAsset("aud_$it", FileId("$it"), it, "mp3", FileMs, null, null) }
        val chapters = titles.mapIndexed { index, title ->
            AudioChapter("$index", title, "aud_$index", index, index * FileMs, (index + 1) * FileMs, 0)
        }
        return manifest(assets, chapters)
    }

    /** One file holding every chapter. */
    private fun oneFile(titles: List<String>): AudioManifest {
        val asset = AudioAsset("aud_0", FileId("0"), 0, "m4b", titles.size * FileMs, null, null)
        val chapters = titles.mapIndexed { index, title ->
            AudioChapter("$index", title, "aud_0", index, index * FileMs, (index + 1) * FileMs, index * FileMs)
        }
        return manifest(listOf(asset), chapters)
    }

    private fun manifest(assets: List<AudioAsset>, chapters: List<AudioChapter>) = AudioManifest(
        bookId = BookId("1"),
        contentVersion = "rev",
        title = "The Lantern Keeper",
        authors = emptyList(),
        narrators = emptyList(),
        hasCover = false,
        assets = assets,
        chapters = chapters,
        totalDurationMs = assets.sumOf { it.durationMs ?: 0 },
    )

    private companion object {
        const val FileMs = 600_000L
    }
}
