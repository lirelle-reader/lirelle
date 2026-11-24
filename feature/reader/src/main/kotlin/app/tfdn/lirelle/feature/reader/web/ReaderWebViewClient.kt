package app.tfdn.lirelle.feature.reader.web

import android.content.res.AssetManager
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.textclassifier.TextClassifier
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

private const val NormalTextZoom = 100

/** What the page makes for itself in memory: the sections of the book as the engine prepares them. */
private val InPageSchemes = setOf("blob", "data")

/**
 * Answers every request of the web view itself: the reader's origin from the assets and the served book, with a
 * 404 for what is not there, and an empty 403 for any other host, scheme, port or verb. Nothing is left to the
 * network: the only requests handed back to the web view (null) are `blob:` and `data:`, which never leave it and
 * which the content security policy governs. They are not expected here at all, but refusing them would blank
 * every page of a book.
 */
internal class ReaderRequestPolicy(assets: AssetManager, books: BookServer) {
    private val loader = WebViewAssetLoader.Builder()
        .setDomain(ReaderHost)
        .setHttpAllowed(false)
        .addPathHandler("/reader/", AssetFolderHandler(assets, "reader"))
        .addPathHandler("/fonts/", AssetFolderHandler(assets, "fonts"))
        .addPathHandler(BookPrefix, BookPathHandler(books))
        .build()

    fun respond(url: Uri, method: String): WebResourceResponse? = when {
        url.scheme in InPageSchemes -> null
        method != "GET" || !isReaderOrigin(url) -> refused()
        else -> loader.shouldInterceptRequest(url) ?: notFound()
    }
}

/** True for a `blob:` document made by the reader's own origin, the only kind a frame of the page may load. */
internal fun isPageMadeDocument(url: Uri): Boolean =
    url.scheme == "blob" && url.toString().startsWith("blob:https://$ReaderHost/")

/** True for `https://appassets.androidplatform.net`, with or without a path, and nothing else. */
internal fun isReaderOrigin(url: Uri): Boolean =
    url.scheme == "https" && url.host == ReaderHost && url.port == -1 && url.userInfo == null

internal class ReaderWebViewClient(
    private val policy: ReaderRequestPolicy,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        policy.respond(request.url, request.method)

    /**
     * The page is loaded once by the host and never leaves; links are reported over the bridge. The one
     * navigation let through is the engine showing a chapter: a frame of the page loading a `blob:` document the
     * page itself made. Refusing it leaves every book blank.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.isForMainFrame || !isPageMadeDocument(request.url)

    /** Handled, or the app would be killed along with the renderer. The web view must then be replaced. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        onRendererGone()
        return true
    }
}

/** The settings of a web view that shows a book: scripts of our page only, and no way out. */
internal fun configureReaderWebView(webView: WebView) {
    // A web view whose height wraps its content gives its page a viewport height of zero, and the
    // engine then has nowhere to lay the book out.
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.settings.apply {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        domStorageEnabled = false
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        setGeolocationEnabled(false)
        mediaPlaybackRequiresUserGesture = true
        // The engine stops following swipes once the page is zoomed.
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        // The reader has its own font size; the system's font scale must not multiply it.
        textZoom = NormalTextZoom
        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        // Themes are applied by the page; the web view must not darken it a second time.
        isAlgorithmicDarkeningAllowed = false
        // The reader has its own selection bar: nothing is prepared with the selected text for the system's.
        disabledActionModeMenuItems =
            WebSettings.MENU_ITEM_SHARE or WebSettings.MENU_ITEM_WEB_SEARCH or WebSettings.MENU_ITEM_PROCESS_TEXT
    }
    // No smart selection: it would hand the text to the system classifier and may widen the selection.
    webView.setTextClassifier(TextClassifier.NO_OP)
    // A form inside a book is not one of the user's sites: nothing saved on the device is offered to it.
    webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    webView.isVerticalScrollBarEnabled = false
    webView.isHorizontalScrollBarEnabled = false
    webView.overScrollMode = WebView.OVER_SCROLL_NEVER
}
