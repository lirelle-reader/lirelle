package app.tfdn.lirelle.core.comics.store

/** An album of the store: what it weighs, when it was last opened, and whether a reader has it open. */
internal data class KeptAlbum(val name: String, val bytes: Long, val openedAt: Long, val held: Boolean)

/**
 * The albums that go for the rest to fit in [limitBytes]: whole albums, the one opened longest ago first,
 * never one a reader has open, even when those alone are above the limit.
 */
internal fun albumsToDrop(albums: List<KeptAlbum>, limitBytes: Long): List<String> {
    var excess = albums.sumOf { it.bytes } - limitBytes
    return albums.filterNot { it.held }.sortedBy { it.openedAt }.takeWhile { album ->
        (excess > 0).also { excess -= album.bytes }
    }.map { it.name }
}
