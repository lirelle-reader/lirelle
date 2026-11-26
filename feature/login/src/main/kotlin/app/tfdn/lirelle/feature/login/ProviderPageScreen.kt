package app.tfdn.lirelle.feature.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.network.UserAgent
import app.tfdn.lirelle.core.session.ServerKind

/**
 * The page of a provider that signs in through a WebView of the app, over the sign-in step whose view model holds
 * it ([SignInViewModel.providerPage]). When there is none — it ended, or the process was stopped while it was open
 * and the attempt is lost — [onClose] is asked: the sign-in step shows again.
 *
 * @param signInStep what holds the sign-in step's view model: the step under this screen.
 * @param purpose and [again] as the sign-in step was given them.
 */
@Composable
fun ProviderPageRoute(
    signInStep: ViewModelStoreOwner,
    onClose: () -> Unit,
    purpose: SignInPurpose = SignInPurpose.First,
    again: ProfileLabel? = null,
) {
    val viewModel = signInViewModel(purpose, again, signInStep)
    val page by viewModel.providerPage.page.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(page == null) {
        if (page == null) close()
    }
    val cookies = remember { ProviderPageCookies(AndroidWebCookies) }
    page?.let { ProviderPageScreen(it, cookies, viewModel.userAgent, onEnd = viewModel.providerPage::end) }
}

/**
 * The provider's [page] in a WebView, under a bar that names the provider and the host of the page shown, and
 * closes it. The system's Back closes it too: the WebView's own history is not walked back, a sign-in begun again
 * starts from the provider's first page.
 *
 * Every cookie and the pages' storage are removed ([cookies]) before the page opens — an attempt the process lost
 * may have left some — and whenever it ends, the server's session taken first once its page is reached, and handed
 * over with [onEnd]. The provider's own session goes too: kept, it would sign the next account added on that
 * server in as the same one, without asking, and stay on the device.
 *
 * The WebView names itself as a browser does, Lirelle's name and version added at the end ([userAgent]): the
 * provider's pages are served as to a browser, and the server's and the provider's logs say which app signed in.
 */
@Composable
internal fun ProviderPageScreen(
    page: ProviderPage,
    cookies: ProviderPageCookies,
    userAgent: UserAgent,
    onEnd: (ProviderPageEnd) -> Unit,
    modifier: Modifier = Modifier,
) {
    val end by rememberUpdatedState(onEnd)
    val close = remember(page, cookies) {
        {
            cookies.forgetAll()
            end(ProviderPageEnd.Closed)
        }
    }
    var host by remember(page) { mutableStateOf(pageHost(page.address).orEmpty()) }
    BackHandler(onBack = close)
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        ProviderPageBar(page.providerName, host, onClose = close)
        AndroidView(
            factory = { context ->
                cookies.forgetAll()
                WebView(context).apply {
                    configureProviderWebView(this, userAgent)
                    webViewClient = ProviderPageClient(
                        kind = page.kind,
                        serverUrl = page.serverUrl,
                        hasSessionCookie = { cookies.hasSessionCookie(page.kind, page.serverUrl) },
                        onOutcome = { outcome -> end(cookies.endOn(outcome, page.serverUrl)) },
                        onGone = close,
                        onPage = { url -> pageHost(url)?.let { host = it } },
                    )
                    loadUrl(page.address)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
        )
    }
}

/**
 * The provider's name, the host of the page shown under it — whole, on as many lines as it needs: it tells the user
 * whose page asks for their password — and the button that closes the page, named for a screen reader.
 */
@Composable
private fun ProviderPageBar(providerName: String, host: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.provider_page_close))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = providerName,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = MaterialTheme.typography.titleMedium,
            )
            if (host.isNotEmpty()) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val BarHeight = 56.dp

/**
 * The settings of a WebView that shows a provider's sign-in pages: scripts, which those pages need (Authentik's
 * are an application), and nothing more — no file, no content of the device, no bridge to the app, no window of
 * its own, no cleartext part in a secure page. It says what it is: the WebView's own user agent, followed by
 * Lirelle's name and version ([providerUserAgent]).
 */
// Scripts run only in the provider's and the server's pages; no bridge to the app is added to them.
@SuppressLint("SetJavaScriptEnabled")
internal fun configureProviderWebView(webView: WebView, userAgent: UserAgent) {
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    webView.settings.apply {
        javaScriptEnabled = true
        // The provider's pages keep their state in the page's storage, as in a browser.
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        setGeolocationEnabled(false)
        userAgentString = providerUserAgent(userAgentString, userAgent)
    }
}

/**
 * The agent of the provider's WebView: its own ([default]), which pages and providers expect from a browser, with
 * the app's name and version added: `… Chrome/142.0 Mobile Safari/537.36 Lirelle/0.7.0`.
 */
internal fun providerUserAgent(default: String, userAgent: UserAgent): String = "$default ${userAgent.app}"

/**
 * Watches where the provider's flow leads: the page that ends it for a server of [kind] ([hasSessionCookie] tells
 * whether the WebView holds its session, for a kind that needs it) — or a page in cleartext outside the
 * server, which ends it as unencrypted ([providerPageOutcome]) — is not loaded: the WebView stops there, and
 * [onOutcome] is told what it says, once — whether the WebView asks about going there, starts the page, or comes to
 * show it without either. Each page of the main frame that starts is told to [onPage], for the bar. A renderer that
 * dies ends the page as closed ([onGone]): the app would otherwise be killed with it.
 */
internal class ProviderPageClient(
    private val kind: ServerKind,
    private val serverUrl: String,
    private val hasSessionCookie: () -> Boolean,
    private val onOutcome: (ProviderPageOutcome) -> Unit,
    private val onGone: () -> Unit,
    private val onPage: (String) -> Unit,
) : WebViewClient() {
    private var ended = false

    /** Also asked for the redirects of the main frame: the server ends the flow with one. */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.isForMainFrame && arrived(request.url.toString())

    /** Asked for the main frame only, also when no navigation was asked about first: a first load, some redirects. */
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPage(url)
        if (arrived(url)) view.stopLoading()
    }

    /**
     * Asked for every address the WebView comes to show, a move within the page's own document included, which
     * neither asks about a navigation nor starts a page: an application that moves by itself once the server sent
     * the WebView to it — Kavita's — ends the flow here when the session was not yet held as its page started.
     */
    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (arrived(url)) view.stopLoading()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
        if (!ended) {
            ended = true
            onGone()
        }
        return true
    }

    private fun arrived(url: String): Boolean {
        val outcome = providerPageOutcome(kind, serverUrl, url, hasSessionCookie) ?: return false
        if (!ended) {
            ended = true
            onOutcome(outcome)
        }
        return true
    }
}
