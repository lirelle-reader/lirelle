package app.tfdn.lirelle.core.comics.pdf

import android.graphics.Bitmap
import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.fetch.PageStates
import app.tfdn.lirelle.core.comics.fetch.imageSize
import app.tfdn.lirelle.core.comics.fetch.windowOrder
import app.tfdn.lirelle.core.comics.store.Album
import app.tfdn.lirelle.core.comics.store.ComicStore
import app.tfdn.lirelle.core.network.ApiError
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException

private const val JpegQuality = 90
private const val PartSuffix = ".part"

/** What the reader tells the drawer. They are handled one at a time, in their order, between two drawings. */
internal sealed interface DrawCommand {
    /** The reader arrived on the page, or asks again for one that failed. */
    data class Show(val index: Int) : DrawCommand

    data class Settle(val index: Int) : DrawCommand

    data class Jump(val index: Int) : DrawCommand

    /** Somebody looks at the page: if the device has it, it is told so. */
    data class Restore(val index: Int) : DrawCommand

    /** The page is shown where its picture needs [longSidePx] pixels on its long side. */
    data class Sharpen(val index: Int, val longSidePx: Int) : DrawCommand
}

/**
 * Decides which pages of a PDF are drawn and when, and draws them into the [album]'s folder, one at a time:
 * a single coroutine tells it its [DrawCommand]s and asks it to draw, so that nothing here is shared between
 * threads but [states], and the [opened] document serves one caller.
 */
internal class PageDrawer(
    private val album: Album,
    private val store: ComicStore,
    private val states: PageStates,
    private val opened: Opened,
) {
    private var plan = DrawPlan()
    private val kept = KeptPages(album)

    /** The smallest long side each page could not be drawn again for: nothing that large is tried again. */
    private val tooLarge = mutableMapOf<Int, Int>()

    fun handle(command: DrawCommand) {
        when (command) {
            is DrawCommand.Show -> show(command.index)
            is DrawCommand.Settle -> settle(command.index)
            is DrawCommand.Jump -> {
                // What waited is dropped; the drawing being made, if any, ends and is kept.
                plan = DrawPlan()
                show(command.index)
                settle(command.index)
            }
            is DrawCommand.Restore -> onDevice(command.index)
            is DrawCommand.Sharpen -> sharpen(command.index, command.longSidePx)
        }
    }

    /** Makes the next drawing of the plan; false when there is none to make. */
    fun drawNext(): Boolean {
        val drawing = plan.next() ?: return false
        plan = plan.started(drawing)
        // The page may have been drawn since it was planned, for the window or for the reader.
        if (needsDrawing(kept.of(drawing.index)?.longSide, drawing.longSide)) draw(drawing)
        return true
    }

    private fun show(index: Int) {
        when {
            index !in 0 until opened.count -> states[index] = PageState.Failed(ApiError.NotFound)
            !onDevice(index) -> plan = plan.shown(index)
        }
    }

    private fun settle(index: Int) {
        plan = plan.settled(windowOrder(index, opened.count).filter { !onDevice(it) })
    }

    private fun sharpen(index: Int, longSidePx: Int) {
        if (index !in 0 until opened.count || !needsDrawing(kept.of(index)?.longSide, longSidePx)) return
        val longSide = boundedLongSide(longSidePx)
        if (tooLarge[index]?.let { longSide >= it } == true) return
        plan = plan.sharpened(Drawing(index, longSide))
    }

    /**
     * Whether the device has a picture of the page, which is then told to those who watch it. A file that is
     * no picture, as a drawing cut by the end of the process could leave, goes.
     */
    private fun onDevice(index: Int): Boolean {
        val page = kept.of(index) ?: return false
        val told = states.of(index).value
        if (told is PageState.Ready && told.file == page.file) return true
        val size = imageSize(page.file)
        if (size == null) kept.drop(index) else states[index] = PageState.Ready(page.file, size.width, size.height)
        return size != null
    }

    /**
     * Draws the page and keeps its picture. A page drawn again larger stays as it is shown until the new
     * picture is there, and if that drawing fails: the smaller picture is still a page, and a picture that
     * large is not tried again.
     */
    private fun draw(drawing: Drawing) {
        val index = drawing.index
        val sharper = kept.of(index) != null
        if (!sharper) states[index] = PageState.Loading
        val drawn = pictureOf(drawing)
        when {
            drawn != null -> {
                val replaced = kept.keep(drawing)
                states[index] = drawn
                replaced?.delete()
                store.trim()
            }
            sharper -> tooLarge[index] = minOf(tooLarge[index] ?: drawing.longSide, drawing.longSide)
            else -> states[index] = PageState.Failed(ApiError.Unexpected)
        }
    }

    /**
     * The picture of the [drawing], in its place; null when it could not be made, whatever the reason. The
     * system's renderer throws more than it says, and a picture of the largest size may not fit in the memory
     * left: neither is to end the app.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun pictureOf(drawing: Drawing): PageState.Ready? = try {
        val place = kept.place(drawing)
        val size = pixelSize(opened.document.pageSize(drawing.index), drawing.longSide)
        val picture = opened.document.render(drawing.index, size)
        try {
            picture.writeTo(place, album)
        } finally {
            picture.recycle()
        }
        PageState.Ready(place, size.width, size.height)
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/**
 * Written beside its [place] under a name of its own and moved there in one step, so that a file in place is
 * always a whole picture. A JPEG: the page is opaque, and no other format the system writes is as quick to
 * make and to read by regions. Nothing is written once the [album]'s profile closed.
 */
private fun Bitmap.writeTo(place: File, album: Album) {
    val part = File(place.parentFile, "${place.name}.${UUID.randomUUID()}$PartSuffix")
    try {
        if (!album.makeFolder()) throw IOException("The album's folder is not there")
        val written = part.outputStream().use { compress(Bitmap.CompressFormat.JPEG, JpegQuality, it) }
        if (!written) throw IOException("The page's picture could not be written")
        Files.move(part.toPath(), place.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        part.delete()
    }
}
