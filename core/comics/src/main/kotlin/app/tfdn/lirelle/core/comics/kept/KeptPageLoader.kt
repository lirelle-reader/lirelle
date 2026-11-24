package app.tfdn.lirelle.core.comics.kept

import app.tfdn.lirelle.core.comics.PageState
import app.tfdn.lirelle.core.comics.fetch.Command
import app.tfdn.lirelle.core.comics.fetch.PageStates
import app.tfdn.lirelle.core.comics.fetch.imageSize
import app.tfdn.lirelle.core.library.offline.KeptPages
import app.tfdn.lirelle.core.network.ApiError

/**
 * Tells what the device has of the pages of an album the downloads keep whole: each is its kept file, read
 * where it is, and nothing is ever asked of the server. There is nothing to fetch ahead, so only the pages
 * somebody shows or watches are looked at: the size of a picture is read from its file's header then.
 *
 * A kept page that is missing, or is no picture, has failed, as the kept copy of a document that cannot be
 * read is said damaged: the server's page is not put in its place, and asking again reads the kept file again.
 *
 * It is told one [Command] at a time by a single coroutine, where files may be read.
 */
internal class KeptPageLoader(private val pages: KeptPages, private val states: PageStates) {
    fun handle(command: Command) {
        when (command) {
            is Command.Show -> look(command.index, again = true)
            is Command.Jump -> look(command.index, again = true)
            is Command.Restore -> look(command.index, again = false)
            is Command.Settle, is Command.Done -> Unit
        }
    }

    /** A page that failed is read [again] only when the reader asks for it, not for being watched. */
    private fun look(index: Int, again: Boolean) {
        val known = states.of(index).value
        val stands = when (known) {
            is PageState.Ready -> known.file.isFile
            is PageState.Failed -> !again
            PageState.Loading -> false
        }
        if (!stands) states[index] = read(index)
    }

    private fun read(index: Int): PageState {
        if (index !in 0 until pages.count) return PageState.Failed(ApiError.NotFound)
        val file = pages.file(index)
        val size = imageSize(file) ?: return PageState.Failed(ApiError.Unexpected)
        return PageState.Ready(file, size.width, size.height)
    }
}
