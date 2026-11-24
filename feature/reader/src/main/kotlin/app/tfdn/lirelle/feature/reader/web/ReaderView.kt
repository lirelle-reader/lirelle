package app.tfdn.lirelle.feature.reader.web

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/**
 * The reader's web view. The system's floating selection toolbar is not wanted: the reader draws its own bar.
 * The mode itself must still start, though, for Chromium clears the selection when it is refused or finished,
 * so the floating mode is given a callback whose menu stays empty.
 */
internal class ReaderView(context: Context) : WebView(context) {

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(wrap(callback, type), type)

    /** [callback] as the web view should see it for a mode of [type]. */
    internal fun wrap(callback: ActionMode.Callback, type: Int): ActionMode.Callback =
        if (type == ActionMode.TYPE_FLOATING) EmptyMenu(callback) else callback
}

private class EmptyMenu(private val wrapped: ActionMode.Callback) : ActionMode.Callback2() {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        wrapped.onCreateActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        wrapped.onPrepareActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

    override fun onDestroyActionMode(mode: ActionMode) = wrapped.onDestroyActionMode(mode)

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        (wrapped as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect)
            ?: super.onGetContentRect(mode, view, outRect)
    }
}
