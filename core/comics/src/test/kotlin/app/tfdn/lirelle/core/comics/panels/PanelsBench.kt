package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.testing.FakeComicPages
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher

private const val PictureBytes = 10
private const val PictureWidth = 800
private const val PictureHeight = 1200

/**
 * An album whose pages the test puts on the device, a store of zones in a folder of its own, and a cutter that
 * cuts nothing: it notes in [cut] the files it was given, in order, and answers what [answer] says.
 */
internal class PanelsBench {
    val directory: File = Files.createTempDirectory("panels").toFile()
    private val pictures: File = Files.createTempDirectory("pictures").toFile()
    val key = ComicKey(FileId("7"), "v1")
    val pages = FakeComicPages()
    val store = ZoneStore(directory)
    val dispatcher = StandardTestDispatcher()

    /** What every page is cut into, unless [answer] says otherwise. */
    val zones = listOf(
        Zone(0.05f, 0.05f, 0.5f, 0.3f, ZoneKind.Filled),
        Zone(0.05f, 0.35f, 0.95f, 0.6f, ZoneKind.Sparse),
    )

    /** The files the cutter was given, and the direction of each, in order. */
    val cut = mutableListOf<Pair<File, Direction>>()

    /** What the cutter's next call waits for before it answers; that call alone. */
    var gate: CompletableDeferred<Unit>? = null

    /** What the cutter makes of a file. */
    var answer: (File) -> CutOutcome = { CutOutcome.Zones(zones) }

    /** The files the cutter was given, in order. */
    val cutFiles: List<File> get() = cut.map { it.first }

    /** Where the picture of the page [index] is. */
    fun file(index: Int): File = File(pictures, index.toString())

    /** The picture of the page [index] is on the device; each page's is of another length. */
    fun ready(index: Int) {
        file(index).writeBytes(ByteArray(PictureBytes + index))
        pages.set(index, PageState.Ready(file(index), PictureWidth, PictureHeight))
    }

    /**
     * The zones of the album, the cuts in [scope] on the test's [dispatcher]; of the pages [watched] and kept in
     * [zoneStore], the bench's own unless the test has others.
     */
    fun open(scope: CoroutineScope, watched: ComicPages = pages, zoneStore: ZoneStore = store): PagePanels =
        DefaultPagePanels(key, watched, zoneStore, scope, dispatcher) { file, direction ->
            cut += file to direction
            val waited = gate
            gate = null
            waited?.await()
            answer(file)
        }
}
