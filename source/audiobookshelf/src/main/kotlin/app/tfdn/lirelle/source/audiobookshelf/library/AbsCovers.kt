package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto

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

/** The width a list's tile asks for, in pixels: enough for the widest tile on a tablet. */
private const val TileWidth = 400

/** The width a book's page asks for, in pixels. */
private const val PageWidth = 800
