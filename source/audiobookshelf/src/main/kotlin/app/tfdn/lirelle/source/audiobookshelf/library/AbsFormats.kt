package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.isEpubFormat
import app.tfdn.lirelle.core.library.model.pagedKindOf
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMediaDto

/**
 * What an Audiobookshelf item holds, from whichever shape of it came: audio when it counts tracks or audio files,
 * or lists some (a whole item on 2.26 counts nothing); its ebook's format from the format it names, else from its
 * ebook file. An EPUB, a PDF or a comic (CBZ, CBR) is a format Lirelle reads; another ebook (MOBI, AZW3) is none,
 * and the book is still listed.
 */
internal object AbsFormats {

    /** The format groups the item is in: an audiobook with an EPUB is in both. */
    fun of(item: AbsItemDto): Set<FormatFilter> = buildSet {
        if (item.media.hasAudio) add(FormatFilter.Audio)
        val ebook = item.media.ebookFormat()
        when {
            isEpubFormat(ebook) -> add(FormatFilter.Epub)
            pagedKindOf(ebook) == PagedKind.Pdf -> add(FormatFilter.Pdf)
            pagedKindOf(ebook) == PagedKind.Comic -> add(FormatFilter.Comics)
        }
    }

    /** What the item offers: audio, an ebook of any format, both, or nothing told (as text, the app's default). */
    fun media(item: AbsItemDto): MediaProfile {
        val audio = item.media.hasAudio
        val text = item.media.ebookFormat() != null
        return when {
            audio && text -> MediaProfile.TextAndAudio
            audio -> MediaProfile.AudioOnly
            else -> MediaProfile.TextOnly
        }
    }

    /** What the pages of the item's ebook are, for a PDF or a comic; null for any other item. */
    fun pagedKind(item: AbsItemDto): PagedKind? = pagedKindOf(item.media.ebookFormat())
}

/** The item has audio: counted on a list's item and on 2.38's whole item, listed on 2.26's. */
private val AbsMediaDto.hasAudio: Boolean
    get() = (numTracks ?: 0) > 0 || (numAudioFiles ?: 0) > 0 || tracks.isNotEmpty() || audioFiles.isNotEmpty()

/** The format of the item's ebook, in lower case; null for an item without one. */
private fun AbsMediaDto.ebookFormat(): String? =
    (ebookFormat ?: ebookFile?.ebookFormat)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
