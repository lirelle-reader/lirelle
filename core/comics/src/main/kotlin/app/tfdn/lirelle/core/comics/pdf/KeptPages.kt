package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.store.Album
import java.io.File

/** A page drawn: `<index>-<long side>` in the album's folder. */
private val PageName = Regex("""(\d+)-(\d+)""")

/** The picture kept of a page, drawn for [longSide] pixels on its long side. */
internal data class KeptPage(val file: File, val longSide: Int)

/**
 * The pages drawn of an [album], each kept once, at the largest size it was drawn for. A picture is named after
 * its page and its size, so that a page drawn again larger is a new file: who shows the old one is told of the
 * new one before the old one goes.
 *
 * It is used by the one coroutine that draws.
 */
internal class KeptPages(private val album: Album) {
    private var pages: MutableMap<Int, KeptPage>? = null

    /** The picture kept of the page [index], or null: one whose file went since is not kept any more. */
    fun of(index: Int): KeptPage? {
        val page = known()[index] ?: return null
        if (page.file.isFile) return page
        known().remove(index)
        return null
    }

    /** Where the picture of a [drawing] goes. */
    fun place(drawing: Drawing): File = File(album.directory, "${drawing.index}-${drawing.longSide}")

    /** The [drawing] is in its place: gives the picture it replaces, which is the caller's to remove. */
    fun keep(drawing: Drawing): File? {
        val file = place(drawing)
        return known().put(drawing.index, KeptPage(file, drawing.longSide))?.file?.takeIf { it != file }
    }

    /** The picture of the page [index] turned out to be none. */
    fun drop(index: Int) {
        known().remove(index)?.file?.delete()
    }

    private fun known(): MutableMap<Int, KeptPage> = pages ?: found().also { pages = it }

    /** What an earlier opening left: the largest picture of each page, the smaller ones removed. */
    private fun found(): MutableMap<Int, KeptPage> {
        val pictures = album.directory.listFiles().orEmpty().mapNotNull { file ->
            val name = PageName.matchEntire(file.name)?.groupValues ?: return@mapNotNull null
            val index = name[1].toIntOrNull() ?: return@mapNotNull null
            val longSide = name[2].toIntOrNull() ?: return@mapNotNull null
            index to KeptPage(file, longSide)
        }
        val largest = pictures.groupBy({ it.first }, { it.second })
            .mapValues { (_, kept) -> kept.maxBy { it.longSide } }
        pictures.filter { (index, page) -> largest[index] != page }.forEach { (_, page) -> page.file.delete() }
        return largest.toMutableMap()
    }
}
