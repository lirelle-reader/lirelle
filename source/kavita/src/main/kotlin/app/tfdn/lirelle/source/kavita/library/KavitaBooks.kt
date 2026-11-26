package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDetailDto
import app.tfdn.lirelle.source.kavita.library.dto.VolumeDto
import java.math.BigDecimal
import kotlin.math.abs

/** The number Kavita gives the default chapter: the one of a volume that is a single file. */
internal const val DefaultChapter = "-100000"

/**
 * What a Lirelle book is in Kavita: a volume that is one file, or a chapter (a special is a chapter). Its book id
 * says which, `v12` or `c34`, so that the book's page knows what to ask and Kavita's two numberings never mix.
 */
internal sealed interface KavitaItem {
    val bookId: BookId

    /**
     * A volume read through its default chapter: its only chapter, as a rule. A default chapter beside other
     * chapters still makes the volume the book, as it does seen alone ([item]).
     */
    data class Volume(val id: Int) : KavitaItem {
        override val bookId = BookId("$VolumePrefix$id")
    }

    /** A chapter of a volume, a chapter outside any volume, or a special. */
    data class Chapter(val id: Int) : KavitaItem {
        override val bookId = BookId("$ChapterPrefix$id")
    }
}

/** What Kavita holds under this book id; null for an id that is not one of a Kavita book. */
internal fun BookId.kavitaItem(): KavitaItem? {
    val number = value.drop(1).takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }?.toIntOrNull()
    return when {
        number == null || number <= 0 -> null
        value.startsWith(VolumePrefix) -> KavitaItem.Volume(number)
        value.startsWith(ChapterPrefix) -> KavitaItem.Chapter(number)
        else -> null
    }
}

/** The chapter is the default chapter of its volume: the volume is the book. */
internal val ChapterDto.isDefault: Boolean get() = number == DefaultChapter && !isSpecial

/** A chapter seen alone (Home, Search, a reading list): the book it belongs to. */
internal fun ChapterDto.item(): KavitaItem = if (isDefault) KavitaItem.Volume(volumeId) else KavitaItem.Chapter(id)

/**
 * One book: its [item], the [chapter] that carries its title, pages and people, its place in the series ([index]:
 * the volume's number, the chapter's range, none for a special) and whether it is a [special].
 */
internal data class KavitaBook(val item: KavitaItem, val chapter: ChapterDto, val index: String?, val special: Boolean)

/**
 * A chapter seen alone as a book: the default chapter is its volume, whose [volumeIndex] the caller gives (the
 * volume's [index], from `GET api/Volume`); any other chapter is itself, and [volumeIndex] is not read. A special
 * has no number.
 */
internal fun ChapterDto.book(volumeIndex: String?): KavitaBook = when {
    isDefault -> KavitaBook(KavitaItem.Volume(volumeId), this, volumeIndex.orNullIfBlank(), special = false)
    isSpecial -> KavitaBook(KavitaItem.Chapter(id), this, index = null, special = true)
    else -> KavitaBook(KavitaItem.Chapter(id), this, range.orIndex(), special = false)
}

/**
 * The volume's books: the volume itself when its only chapter is the default one, else its chapters by sort order.
 * A default chapter beside others still stands for the volume, as it does seen alone ([item]).
 */
internal fun VolumeDto.books(): List<KavitaBook> = chapters.sortedBy { it.sortOrder }.map {
    if (it.isDefault) KavitaBook(KavitaItem.Volume(id), it, index, special = false) else it.book(index)
}.distinctBy { it.item }

/**
 * The volume's place in its series: "3", "10.5", or "1-3" for a volume that spans several numbers; its whole
 * [VolumeDto.number] from a server that gives no other. None for 0, nor for the volumes Kavita numbers ±100000:
 * the loose-leaf volume of the chapters outside a volume, and that of the specials.
 */
internal val VolumeDto.index: String?
    get() = when {
        minNumber.isSentinel() || maxNumber.isSentinel() || number.toFloat().isSentinel() -> null
        maxNumber > minNumber -> "${minNumber.plain()}-${maxNumber.plain()}"
        minNumber != 0f -> minNumber.plain()
        number != 0 -> number.toString()
        else -> null
    }

/** The volume's number to order it by, a decimal kept. */
private val VolumeDto.order: Float get() = if (minNumber != 0f || maxNumber != 0f) minNumber else number.toFloat()

/** Kavita's number of the loose-leaf volume (-100000) or of the specials' (100000), which is no number. */
private fun Float.isSentinel(): Boolean = abs(this) == SentinelVolume

/** The number as written, without a trailing ".0": "10", "10.5". */
private fun Float.plain(): String = BigDecimal(toString()).stripTrailingZeros().toPlainString()

/**
 * The series' books in order (spec §2, §5): volumes by number, each the volume itself when its only chapter is
 * the default one, else its chapters by sort order; then the chapters outside a volume; then the specials.
 * `chapters` (every chapter again) is not read. Each book once.
 */
internal fun SeriesDetailDto.books(): List<KavitaBook> {
    val inVolumes = volumes.sortedBy { it.order }.flatMap { it.books() }
    // Outside a volume a chapter is always a book of its own, even one numbered as a default chapter: Kavita makes
    // such a file a special, and were it not, the loose-leaf volume it sits in holds every chapter outside a volume,
    // so that volume's id would not name this book alone.
    val loose = storylineChapters.sortedBy { it.sortOrder }.map {
        KavitaBook(KavitaItem.Chapter(it.id), it, if (it.isSpecial) null else it.range.orIndex(), it.isSpecial)
    }
    val extras = specials.sortedBy { it.sortOrder }.map { KavitaBook(KavitaItem.Chapter(it.id), it, null, true) }
    return (inVolumes + loose + extras).distinctBy { it.item }
}

/** A series that holds one special and nothing else: shown as a lone book, without a series. */
internal val SeriesDetailDto.isLoneBook: Boolean
    get() = volumes.isEmpty() && storylineChapters.isEmpty() && specials.size == 1

/** A chapter's range as its place in the series; the default chapter's number is none. */
internal fun String?.orIndex(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != DefaultChapter }

private const val SentinelVolume = 100_000f
private const val VolumePrefix = "v"
private const val ChapterPrefix = "c"
