package app.tfdn.lirelle.feature.reader.web

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

private const val RectEdge = 7

/** Records what reaches it; adds one item so that a cleared menu can be told from an untouched one. */
private class RecordingCallback : ActionMode.Callback2() {
    var created = 0
    var prepared = 0
    var destroyed = 0
    var rectAsked = 0

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        created++
        menu.add("Copy")
        return false
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        prepared++
        menu.add("Share")
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = true

    override fun onDestroyActionMode(mode: ActionMode) {
        destroyed++
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        rectAsked++
        outRect.set(RectEdge, RectEdge, RectEdge, RectEdge)
    }
}

@RunWith(AndroidJUnit4::class)
class ReaderViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val view = ReaderView(context)

    private fun wrapped(callback: ActionMode.Callback): ActionMode.Callback =
        view.wrap(callback, ActionMode.TYPE_FLOATING)

    private fun menu(): Menu = PopupMenu(context, view).menu

    private fun mode(): ActionMode = object : ActionMode() {
        override fun setTitle(title: CharSequence?) = Unit
        override fun setTitle(resId: Int) = Unit
        override fun setSubtitle(subtitle: CharSequence?) = Unit
        override fun setSubtitle(resId: Int) = Unit
        override fun setCustomView(view: View?) = Unit
        override fun invalidate() = Unit
        override fun finish() = Unit
        override fun getMenu(): Menu = menu()
        override fun getTitle(): CharSequence? = null
        override fun getSubtitle(): CharSequence? = null
        override fun getCustomView(): View? = null
        override fun getMenuInflater(): MenuInflater? = null
    }

    @Test
    fun `the floating mode is created and prepared with an empty menu, and the wrapped callback is still called`() {
        val inner = RecordingCallback()
        val outer = wrapped(inner)
        val menu = menu()

        assertThat(outer.onCreateActionMode(mode(), menu)).isTrue()
        assertThat(outer.onPrepareActionMode(mode(), menu)).isTrue()

        assertThat(inner.created).isEqualTo(1)
        assertThat(inner.prepared).isEqualTo(1)
        assertThat(menu.size()).isEqualTo(0)
    }

    @Test
    fun `no item of the floating mode is handled, the end and the rectangle go to the wrapped callback`() {
        val inner = RecordingCallback()
        val outer = wrapped(inner) as ActionMode.Callback2
        val rect = Rect()

        assertThat(outer.onActionItemClicked(mode(), menu().add("x"))).isFalse()
        outer.onGetContentRect(mode(), view, rect)
        outer.onDestroyActionMode(mode())

        assertThat(rect).isEqualTo(Rect(RectEdge, RectEdge, RectEdge, RectEdge))
        assertThat(inner.rectAsked).isEqualTo(1)
        assertThat(inner.destroyed).isEqualTo(1)
    }

    @Test
    fun `a primary mode is passed through unchanged`() {
        val inner = RecordingCallback()

        assertThat(view.wrap(inner, ActionMode.TYPE_PRIMARY)).isSameInstanceAs(inner)
    }

    @Test
    fun `the host builds a reader view`() {
        val host = ReaderWebViewHost(context, onRendererGone = {}, registrar = { _, _, _, _ -> true })

        assertThat(host.webView).isInstanceOf(ReaderView::class.java)
    }
}
