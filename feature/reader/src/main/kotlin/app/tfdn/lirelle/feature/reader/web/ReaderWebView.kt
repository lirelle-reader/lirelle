package app.tfdn.lirelle.feature.reader.web

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.tfdn.lirelle.feature.reader.BookContent
import app.tfdn.lirelle.feature.reader.bridge.ReaderEngine

/**
 * The web view that shows the reader page. It is created once, loads the page from the app's own origin, and is
 * destroyed when it leaves the composition.
 *
 * [book] is what the page may read: each time it changes it is served under a new address, which the engine
 * gives as [ReaderEngine.bookBase] for the `open` message. Nothing of it is closed here.
 *
 * [onEngine] receives the engine when the web view is created. Should the web view's renderer die, the web view
 * is replaced and [onEngine] is called again with the engine of the new one, whose page starts afresh.
 *
 * [background] is what shows before the page has drawn and around it: give the reading theme's colour.
 */
@Composable
fun ReaderWebView(
    book: BookContent?,
    onEngine: (ReaderEngine) -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.background,
) {
    val context = LocalContext.current
    val currentOnEngine by rememberUpdatedState(onEngine)
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        val host = remember(context) { ReaderWebViewHost(context, onRendererGone = { generation++ }) }
        DisposableEffect(host) {
            currentOnEngine(host.engine)
            onDispose { host.destroy() }
        }
        DisposableEffect(host, book) {
            host.serve(book)
            onDispose { }
        }
        AndroidView(
            factory = { host.webView },
            modifier = modifier,
            update = { it.setBackgroundColor(background.toArgb()) },
        )
    }
}
