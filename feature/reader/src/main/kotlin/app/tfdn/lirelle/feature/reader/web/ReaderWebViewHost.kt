package app.tfdn.lirelle.feature.reader.web

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.tfdn.lirelle.feature.reader.BookContent
import app.tfdn.lirelle.feature.reader.bridge.BridgeCodec
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageErrorCode
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.bridge.ReaderEngine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

private const val Tag = "ReaderBridge"

/** The name the page posts to: `window.lirelleHost.postMessage(json)`. */
internal const val BridgeName = "lirelleHost"

/** Messages kept while nobody collects; beyond that the oldest go, so a page cannot fill the memory. */
private const val PendingMessages = 256

/** Lets a test stand in for the system when the listener is registered. */
internal fun interface BridgeRegistrar {
    /** False when this web view cannot carry messages. */
    fun register(
        webView: WebView,
        name: String,
        origins: Set<String>,
        listener: WebViewCompat.WebMessageListener,
    ): Boolean
}

internal val SystemBridgeRegistrar = BridgeRegistrar { webView, name, origins, listener ->
    WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER).also { supported ->
        if (supported) WebViewCompat.addWebMessageListener(webView, name, origins, listener)
    }
}

/**
 * Lets through what the reader page itself says, decoded. The frames that show the book share the page's origin,
 * so the origin alone is not enough: only the main frame is listened to.
 */
internal class PageMessageGate(private val onMessage: (PageMessage) -> Unit) : WebViewCompat.WebMessageListener {

    fun accept(data: String?, sourceOrigin: Uri, isMainFrame: Boolean) {
        if (!isMainFrame || !isReaderOrigin(sourceOrigin) || data == null) return
        val message = BridgeCodec.decode(data)
        if (message == null) Log.w(Tag, "Dropped a message that is not of the contract") else onMessage(message)
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val data = if (message.type == WebMessageCompat.TYPE_STRING) message.data else null
        accept(data, sourceOrigin, isMainFrame)
    }
}

/** One web view showing the reader page, and the engine that speaks to it. Main thread only. */
internal class ReaderWebViewHost(
    context: Context,
    onRendererGone: () -> Unit,
    registrar: BridgeRegistrar = SystemBridgeRegistrar,
) {
    private val books = BookServer()
    private val incoming = Channel<PageMessage>(PendingMessages, BufferOverflow.DROP_OLDEST)
    private var destroyed = false

    val webView: WebView = ReaderView(context)

    val gate = PageMessageGate { incoming.trySend(it) }

    val engine: ReaderEngine = object : ReaderEngine {
        override val messages: Flow<PageMessage> = incoming.receiveAsFlow()
        override val bookBase: StateFlow<String?> = books.base

        override fun send(message: HostMessage) {
            if (!destroyed) webView.evaluateJavascript(BridgeCodec.receiveCall(message), null)
        }
    }

    init {
        configureReaderWebView(webView)
        webView.webViewClient = ReaderWebViewClient(ReaderRequestPolicy(context.assets, books), onRendererGone)
        if (!registrar.register(webView, BridgeName, setOf(ReaderOrigin), gate)) {
            incoming.trySend(PageMessage.Error(PageErrorCode.UnsupportedEngine, "No message channel in this web view"))
        }
        webView.loadUrl(ReaderPageUrl)
    }

    /** Gives the page another book to read, or none; the engine's `bookBase` follows. */
    fun serve(book: BookContent?) {
        if (!destroyed) books.serve(book)
    }

    fun destroy() {
        if (destroyed) return
        books.serve(null)
        destroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}
