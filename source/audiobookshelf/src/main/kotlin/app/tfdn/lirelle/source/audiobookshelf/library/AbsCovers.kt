package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsLibraryDto

/**
 * The covers of the items of the profile's server, at [serverUrl]: its cover route, which asks for no
 * authentication, at the width the place shows, with the item's `updatedAt` so that a changed cover is a new address
 * and the image cache stays right. The server sends WebP to a client that accepts it. An item without a cover has
 * no address: the screens show their default tile, and the server is not asked for nothing.
 */
internal class AbsCovers(private val serverUrl: String) {

    /** The cover of an item in a list. */
    fun tile(item: AbsItemDto): String? = url(item, TileWidth)

    /** The cover of an item on its page. */
    fun page(item: AbsItemDto): String? = url(item, PageWidth)

    private fun url(item: AbsItemDto, width: Int): String? {
        if (item.media.coverPath.isNullOrEmpty()) return null
        val version = item.updatedAt?.let { "&ts=$it" }.orEmpty()
        return "$serverUrl/api/items/${item.id}/cover?width=$width$version"
    }
}

/** The shape of the covers of this library, as its settings say: square, or a book's (the default). */
internal val AbsLibraryDto.coverShape: CoverShape
    get() = if (settings?.coverAspectRatio == SquareCovers) CoverShape.Square else CoverShape.Portrait

/** The width a list's tile asks for, in pixels: enough for the widest tile on a tablet. */
private const val TileWidth = 400

/** The width a book's page asks for, in pixels. */
private const val PageWidth = 800

/** What a library's settings say of square covers. */
private const val SquareCovers = 0
