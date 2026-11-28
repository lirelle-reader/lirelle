package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMediaDto
import app.tfdn.lirelle.source.audiobookshelf.network.AbsJson
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.expandedItem
import app.tfdn.lirelle.source.audiobookshelf.testing.fixtureBody
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedItem
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedItems
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** An item made up with the [ebookFormat] and the [tracks] given: the test server holds no MOBI nor AZW3. */
private fun madeUp(ebookFormat: String?, tracks: Int = 0) =
    AbsItemDto(id = "made-up", media = AbsMediaDto(ebookFormat = ebookFormat, numTracks = tracks))

/**
 * An item's formats, from whichever shape it comes in: a list's item counts its tracks and names its ebook's format
 * on every version; a whole item on 2.26.3 does neither, its tracks and its ebook file say it.
 */
class AbsFormatsTest {

    @Test
    fun `an audiobook with an EPUB is one book in two formats`() {
        RecordedOn.entries.forEach { on ->
            val item = recordedItem("Mostly Harmless", on = on)

            assertWithMessage(on.name).that(AbsFormats.of(item)).containsExactly(FormatFilter.Audio, FormatFilter.Epub)
            assertWithMessage(on.name).that(AbsFormats.media(item)).isEqualTo(MediaProfile.TextAndAudio)
            assertWithMessage(on.name).that(AbsFormats.pagedKind(item)).isNull()
        }
    }

    @Test
    fun `an audiobook alone, without any ebook format, is audio only`() {
        RecordedOn.entries.forEach { on ->
            val raw = AbsJson.parseToJsonElement(fixtureBody("items-livres-all", on)).jsonObject["results"]!!
            val einstein = raw.jsonArray.map { it.jsonObject }.first { "Albert Einstein" in it.toString() }
            val item = recordedItem("Albert Einstein. L'homme le plus surveillé d'Amérique", on = on)

            // Not null: the key is not there at all.
            assertWithMessage(on.name).that(einstein["media"]!!.jsonObject.keys).doesNotContain("ebookFormat")
            assertWithMessage(on.name).that(AbsFormats.of(item)).containsExactly(FormatFilter.Audio)
            assertWithMessage(on.name).that(AbsFormats.media(item)).isEqualTo(MediaProfile.AudioOnly)
        }
    }

    @Test
    fun `an EPUB alone, a PDF and a comic`() {
        RecordedOn.entries.forEach { on ->
            val epub = recordedItem("Globalement inoffensive", on = on)
            val pdf = recordedItem("Causeries historiques sur l'île de la Réunion [fr] (1881)", on = on)
            val comics = recordedItems("items-bd-added", on)

            assertWithMessage(on.name).that(AbsFormats.of(epub)).containsExactly(FormatFilter.Epub)
            assertWithMessage(on.name).that(AbsFormats.media(epub)).isEqualTo(MediaProfile.TextOnly)
            assertWithMessage(on.name).that(AbsFormats.of(pdf)).containsExactly(FormatFilter.Pdf)
            assertWithMessage(on.name).that(AbsFormats.pagedKind(pdf)).isEqualTo(PagedKind.Pdf)
            comics.forEach {
                assertWithMessage(on.name).that(AbsFormats.of(it)).containsExactly(FormatFilter.Comics)
                assertWithMessage(on.name).that(AbsFormats.pagedKind(it)).isEqualTo(PagedKind.Comic)
            }
        }
    }

    @Test
    fun `a whole item says its formats by its tracks and its ebook file, on both versions`() {
        RecordedOn.entries.forEach { on ->
            val guide = expandedItem("item-h2g2-expanded", on)
            val prince = expandedItem("item-petit-prince-expanded", on)
            val asterix = expandedItem("item-asterix-expanded", on)

            assertWithMessage(on.name).that(AbsFormats.of(guide)).containsExactly(FormatFilter.Audio, FormatFilter.Epub)
            assertWithMessage(on.name).that(AbsFormats.of(prince))
                .containsExactly(FormatFilter.Audio, FormatFilter.Epub)
            assertWithMessage(on.name).that(AbsFormats.of(asterix)).containsExactly(FormatFilter.Comics)
        }
    }

    @Test
    fun `the 2_26 whole item has no counts and no ebook format, and still has its formats`() {
        val asterix = expandedItem("item-asterix-expanded", RecordedOn.Minimum)

        assertWithMessage("numTracks").that(asterix.media.numTracks).isNull()
        assertWithMessage("ebookFormat").that(asterix.media.ebookFormat).isNull()
        assertWithMessage("formats").that(AbsFormats.of(asterix)).containsExactly(FormatFilter.Comics)
    }

    @Test
    fun `a CBR is a comic`() {
        assertWithMessage("cbr").that(AbsFormats.of(madeUp("cbr"))).containsExactly(FormatFilter.Comics)
    }

    @Test
    fun `other ebook formats are none Lirelle reads, and the book is still listed with its audio`() {
        listOf("mobi", "azw3", "MOBI").forEach { format ->
            assertWithMessage(format).that(AbsFormats.of(madeUp(format))).isEmpty()
            assertWithMessage(format).that(AbsFormats.of(madeUp(format, tracks = 3)))
                .containsExactly(FormatFilter.Audio)
            assertWithMessage(format).that(AbsFormats.pagedKind(madeUp(format))).isNull()
        }
    }

    @Test
    fun `an item with nothing in it has no format`() {
        assertWithMessage("formats").that(AbsFormats.of(madeUp(null))).isEmpty()
        assertWithMessage("media").that(AbsFormats.media(madeUp(null))).isEqualTo(MediaProfile.TextOnly)
    }

    @Test
    fun `a cover is square for a book that is audio only, on both versions`() {
        RecordedOn.entries.forEach { on ->
            val einstein = recordedItem("Albert Einstein. L'homme le plus surveillé d'Amérique", on = on)

            assertWithMessage(on.name).that(AbsFormats.coverShape(einstein)).isEqualTo(CoverShape.Square)
        }
        assertWithMessage("made up").that(AbsFormats.coverShape(madeUp(null, tracks = 3))).isEqualTo(CoverShape.Square)
    }

    @Test
    fun `a cover is a book's for any ebook, with audio or not, on both versions`() {
        RecordedOn.entries.forEach { on ->
            val withAudio = recordedItem("Mostly Harmless", on = on)
            val epub = recordedItem("Globalement inoffensive", on = on)
            val pdf = recordedItem("Causeries historiques sur l'île de la Réunion [fr] (1881)", on = on)

            assertWithMessage(on.name).that(AbsFormats.coverShape(withAudio)).isEqualTo(CoverShape.Portrait)
            assertWithMessage(on.name).that(AbsFormats.coverShape(epub)).isEqualTo(CoverShape.Portrait)
            assertWithMessage(on.name).that(AbsFormats.coverShape(pdf)).isEqualTo(CoverShape.Portrait)
            recordedItems("items-bd-added", on).forEach {
                assertWithMessage(on.name).that(AbsFormats.coverShape(it)).isEqualTo(CoverShape.Portrait)
            }
        }
    }

    @Test
    fun `a whole item's cover is read from its tracks and its ebook file, on 2_26 too`() {
        RecordedOn.entries.forEach { on ->
            val guide = expandedItem("item-h2g2-expanded", on)
            val asterix = expandedItem("item-asterix-expanded", on)

            assertWithMessage(on.name).that(AbsFormats.coverShape(guide)).isEqualTo(CoverShape.Portrait)
            assertWithMessage(on.name).that(AbsFormats.coverShape(asterix)).isEqualTo(CoverShape.Portrait)
        }
    }

    @Test
    fun `a CBZ, a CBR and an ebook Lirelle does not read keep a book's cover, with audio or not`() {
        listOf("cbz", "cbr", "mobi", "azw3", "MOBI").forEach { format ->
            assertWithMessage(format).that(AbsFormats.coverShape(madeUp(format))).isEqualTo(CoverShape.Portrait)
            assertWithMessage("$format with audio").that(AbsFormats.coverShape(madeUp(format, tracks = 3)))
                .isEqualTo(CoverShape.Portrait)
        }
    }

    @Test
    fun `an item with neither audio nor ebook has a book's cover`() {
        assertWithMessage("shape").that(AbsFormats.coverShape(madeUp(null))).isEqualTo(CoverShape.Portrait)
    }
}
