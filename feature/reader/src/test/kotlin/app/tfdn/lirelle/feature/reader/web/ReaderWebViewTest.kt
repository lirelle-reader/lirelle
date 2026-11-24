package app.tfdn.lirelle.feature.reader.web

import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.view.ViewGroup
import android.view.View
import app.tfdn.lirelle.feature.reader.BookContent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import app.cash.turbine.test
import app.tfdn.lirelle.feature.reader.bridge.BridgeCodec
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageErrorCode
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.bridge.ReaderEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowWebView

private const val Origin = "https://appassets.androidplatform.net"

private fun context(): Context = ApplicationProvider.getApplicationContext()

private class RecordingRegistrar(private val supported: Boolean = true) : BridgeRegistrar {
    var name: String? = null
    var origins: Set<String>? = null
    var listener: WebViewCompat.WebMessageListener? = null

    override fun register(
        webView: WebView,
        name: String,
        origins: Set<String>,
        listener: WebViewCompat.WebMessageListener,
    ): Boolean {
        if (!supported) return false
        this.name = name
        this.origins = origins
        this.listener = listener
        return true
    }
}

@RunWith(AndroidJUnit4::class)
class ReaderHardeningTest {

    private val webView = WebView(context()).also(::configureReaderWebView)
    private val settings: WebSettings = webView.settings

    @Test
    fun `scripts run, and nothing reaches files, content providers or the network`() {
        assertThat(settings.javaScriptEnabled).isTrue()
        assertThat(settings.allowFileAccess).isFalse()
        assertThat(settings.allowContentAccess).isFalse()
        assertThat(settings.blockNetworkLoads).isTrue()
        assertThat(settings.mixedContentMode).isEqualTo(WebSettings.MIXED_CONTENT_NEVER_ALLOW)
        assertThat(settings.domStorageEnabled).isFalse()
    }

    @Test
    fun `the web view fills its container, so the page has a height to lay the book out in`() {
        // With a wrapped height a web view lays its page out with a viewport height of zero.
        assertThat(webView.layoutParams.width).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(webView.layoutParams.height).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
    }

    @Test
    fun `zoom is off and the text keeps the size the page gives it`() {
        assertThat(settings.supportZoom()).isFalse()
        assertThat(settings.builtInZoomControls).isFalse()
        assertThat(settings.displayZoomControls).isFalse()
        assertThat(settings.textZoom).isEqualTo(100)
        // No enlarging of text after layout: the engine measured its columns with the size it was given.
        assertThat(settings.layoutAlgorithm).isEqualTo(WebSettings.LayoutAlgorithm.NORMAL)
    }

    @Test
    fun `no second window, no location, no sound without a gesture`() {
        assertThat(settings.supportMultipleWindows()).isFalse()
        assertThat(settings.javaScriptCanOpenWindowsAutomatically).isFalse()
        assertThat(settings.mediaPlaybackRequiresUserGesture).isTrue()
    }

    @Test
    fun `a form inside a book is never offered what the device has saved`() {
        assertThat(webView.importantForAutofill).isEqualTo(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS)
    }
}

@RunWith(AndroidJUnit4::class)
class ReaderWebViewClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val books = BookServer()
    private var crashes = 0
    private val client = ReaderWebViewClient(ReaderRequestPolicy(context().assets, books)) { crashes++ }
    private val webView = WebView(context())

    private fun get(url: String, verb: String = "GET") =
        checkNotNull(client.shouldInterceptRequest(webView, FakeRequest(url, verb)))

    private fun serveBook(vararg entries: Pair<String, String>): String {
        books.serve(BookContent.Epub(testArchive(folder.newFile(), *entries)))
        return checkNotNull(books.base.value)
    }

    @Test
    fun `the page, its scripts and the engine come from the assets`() {
        val page = get("$Origin/reader/index.html")
        val script = get("$Origin/reader/reader.js")

        assertThat(page.statusCode).isEqualTo(200)
        assertThat(page.mimeType).isEqualTo("text/html")
        assertThat(script.mimeType).isEqualTo("text/javascript")
        assertThat(get("$Origin/reader/foliate/epub.js").statusCode).isEqualTo(200)
        assertThat(page.header("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy)
    }

    @Test
    fun `the reading fonts come from the assets`() {
        val font = get("$Origin/fonts/LirelleSerif-Regular.woff2")

        assertThat(font.statusCode).isEqualTo(200)
        assertThat(font.mimeType).isEqualTo("font/woff2")
    }

    @Test
    fun `a font that is not there is a 404`() {
        val response = get("$Origin/fonts/LirelleNowhere-Regular.woff2")

        assertThat(response.statusCode).isEqualTo(404)
        assertThat(response.header("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy)
    }

    @Test
    fun `the served book is read under its base, with encoded segments decoded once`() {
        val base = serveBook("OEBPS/chapter 1.xhtml" to "one", "OEBPS/100%25 d'été #1?.xhtml" to "odd")

        assertThat(get("$Origin${base}index.json").text()).contains("\"OEBPS/chapter 1.xhtml\":3")
        assertThat(get("$Origin${base}OEBPS/chapter%201.xhtml").text()).isEqualTo("one")
        assertThat(get("$Origin${base}OEBPS/100%2525%20d'%C3%A9t%C3%A9%20%231%3F.xhtml").text()).isEqualTo("odd")
        assertThat(get("$Origin${base}OEBPS/missing.xhtml").statusCode).isEqualTo(404)
        assertThat(get("$Origin${base}OEBPS/%2E%2E/%2E%2E/secret").statusCode).isEqualTo(404)
        assertThat(get("$Origin/book/${"0".repeat(64)}/index.json").statusCode).isEqualTo(404)
    }

    @Test
    fun `anything else on the reader's origin is a 404 that carries the policy`() {
        listOf("$Origin/", "$Origin/index.html", "$Origin/assets/reader/index.html", "$Origin/book/", "$Origin/reader")
            .forEach {
                val response = get(it)

                assertThat(response.statusCode).isEqualTo(404)
                assertThat(response.text()).isEmpty()
                assertThat(response.header("Content-Security-Policy")).isEqualTo(ContentSecurityPolicy)
            }
    }

    @Test
    fun `another host, scheme, port or verb gets an empty 403`() {
        val refused = listOf(
            get("https://example.org/reader/index.html"),
            get("https://appassets.androidplatform.net.example.org/reader/index.html"),
            get("https://evil@example.org/reader/index.html"),
            get("http://appassets.androidplatform.net/reader/index.html"),
            get("https://appassets.androidplatform.net:8443/reader/index.html"),
            get("file:///android_asset/reader/index.html"),
            get("file:///data/data/app.tfdn.lirelle/shared_prefs/session.xml"),
            get("content://media/external/images/1"),
            get("ws://appassets.androidplatform.net/reader/index.html"),
            get("$Origin/reader/index.html", verb = "POST"),
        )

        refused.forEach {
            assertThat(it.statusCode).isEqualTo(403)
            assertThat(it.text()).isEmpty()
        }
    }

    @Test
    fun `what the page holds in memory is left to the web view`() {
        listOf("blob:$Origin/0b1c2d3e-0000-4000-8000-000000000000", "data:image/png;base64,AAAA").forEach {
            assertThat(client.shouldInterceptRequest(webView, FakeRequest(it))).isNull()
        }
    }

    @Test
    fun `every navigation is refused`() {
        listOf(
            "https://example.org/", "$Origin/reader/index.html", "$Origin/book/x/ch1.xhtml", "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/a.html", "market://details?id=x", "about:blank",
        ).forEach {
            assertThat(client.shouldOverrideUrlLoading(webView, FakeRequest(it, mainFrame = true))).isTrue()
            assertThat(client.shouldOverrideUrlLoading(webView, FakeRequest(it, mainFrame = false))).isTrue()
        }
    }

    @Test
    fun `a frame of the page may load a chapter the page made, and nothing else`() {
        val chapter = "blob:$Origin/0b7c2f1e-5a4d-4c1b-9e7a-2f3d4c5b6a79"

        assertThat(client.shouldOverrideUrlLoading(webView, FakeRequest(chapter, mainFrame = false))).isFalse()
        // The page itself never leaves, even for one of its own documents.
        assertThat(client.shouldOverrideUrlLoading(webView, FakeRequest(chapter, mainFrame = true))).isTrue()
        listOf("blob:https://example.org/0b7c2f1e", "blob:null/0b7c2f1e", "blob:$Origin.evil.example/0b7c").forEach {
            assertThat(client.shouldOverrideUrlLoading(webView, FakeRequest(it, mainFrame = false))).isTrue()
        }
    }

    @Test
    fun `a renderer that dies is reported instead of taking the app down`() {
        assertThat(client.onRenderProcessGone(webView, null)).isTrue()
        assertThat(crashes).isEqualTo(1)
    }
}

@RunWith(AndroidJUnit4::class)
class PageMessageGateTest {

    private val received = mutableListOf<PageMessage>()
    private val gate = PageMessageGate { received += it }
    private val origin = Uri.parse(Origin)

    @Test
    fun `a message of the page's main frame is decoded and passed on`() {
        gate.accept("""{"type":"ready"}""", origin, isMainFrame = true)
        gate.accept("""{"type":"tap","x":0.5,"y":0.5}""", Uri.parse("$Origin/"), isMainFrame = true)

        assertThat(received).containsExactly(PageMessage.Ready, PageMessage.Tap(0.5, 0.5)).inOrder()
    }

    @Test
    fun `a message of a frame inside the page is dropped`() {
        gate.accept("""{"type":"ready"}""", origin, isMainFrame = false)

        assertThat(received).isEmpty()
    }

    @Test
    fun `a message of another origin is dropped`() {
        listOf(
            "https://example.org", "http://appassets.androidplatform.net", "https://appassets.androidplatform.net:444",
            "https://appassets.androidplatform.net.example.org", "null", "", "file://", "blob:$Origin/1234",
        ).forEach { gate.accept("""{"type":"ready"}""", Uri.parse(it), isMainFrame = true) }

        assertThat(received).isEmpty()
    }

    @Test
    fun `what does not decode is dropped`() {
        gate.accept(null, origin, isMainFrame = true)
        gate.accept("not json", origin, isMainFrame = true)
        gate.accept("""{"type":"launch"}""", origin, isMainFrame = true)

        assertThat(received).isEmpty()
    }
}

@RunWith(AndroidJUnit4::class)
class ReaderWebViewHostTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val registrar = RecordingRegistrar()
    private val host by lazy { ReaderWebViewHost(context(), onRendererGone = {}, registrar = registrar) }

    @Test
    fun `it is hardened and loads the reader page`() {
        assertThat(host.webView.settings.blockNetworkLoads).isTrue()
        assertThat(host.webView.settings.allowFileAccess).isFalse()
        assertThat(shadowOf(host.webView).webViewClient).isInstanceOf(ReaderWebViewClient::class.java)
        assertThat(shadowOf(host.webView).lastLoadedUrl).isEqualTo("$Origin/reader/index.html")
    }

    @Test
    fun `the listener is registered as lirelleHost for the reader origin only`() {
        host

        assertThat(registrar.name).isEqualTo("lirelleHost")
        assertThat(registrar.origins).containsExactly(Origin)
        assertThat(registrar.listener).isNotNull()
    }

    @Test
    fun `a message is sent as a call of the page's receiver`() {
        val message = HostMessage.GoTo.Href("a\"b</script> ")

        host.engine.send(message)

        assertThat(shadowOf(host.webView).lastEvaluatedJavascript).isEqualTo(BridgeCodec.receiveCall(message))
    }

    @Test
    fun `the engine says where the served book is, anew for each book`() {
        assertThat(host.engine.bookBase.value).isNull()

        host.serve(testBook(folder.newFile()))
        val first = host.engine.bookBase.value
        host.serve(testBook(folder.newFile()))
        val second = host.engine.bookBase.value

        assertThat(first).matches("/book/[0-9a-f]{64}/")
        assertThat(second).matches("/book/[0-9a-f]{64}/")
        assertThat(second).isNotEqualTo(first)
        host.serve(null)
        assertThat(host.engine.bookBase.value).isNull()
    }

    @Test
    fun `what the page says is kept until the engine's messages are collected`() = runTest {
        val gate = host.gate
        gate.accept("""{"type":"ready"}""", Uri.parse(Origin), isMainFrame = true)

        host.engine.messages.test {
            assertThat(awaitItem()).isEqualTo(PageMessage.Ready)
            gate.accept("""{"type":"tap","x":0,"y":1}""", Uri.parse(Origin), isMainFrame = true)
            assertThat(awaitItem()).isEqualTo(PageMessage.Tap(0.0, 1.0))
        }
    }

    @Test
    fun `a web view that cannot carry messages is reported as an unsupported engine`() = runTest {
        val old = ReaderWebViewHost(context(), onRendererGone = {}, registrar = RecordingRegistrar(supported = false))

        old.engine.messages.test {
            assertThat((awaitItem() as PageMessage.Error).code).isEqualTo(PageErrorCode.UnsupportedEngine)
        }
    }

    @Test
    fun `once destroyed it serves nothing and sends nothing`() {
        host.serve(testBook(folder.newFile()))

        host.destroy()
        host.engine.send(HostMessage.Next)

        assertThat(shadowOf(host.webView).wasDestroyCalled()).isTrue()
        assertThat(shadowOf(host.webView).lastEvaluatedJavascript).isNull()
        assertThat(host.engine.bookBase.value).isNull()
    }
}

@RunWith(AndroidJUnit4::class)
class ReaderWebViewTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun webViews(root: View = compose.activity.window.decorView): List<WebView> = when (root) {
        is WebView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { webViews(root.getChildAt(it)) }
        else -> emptyList()
    }

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `it hands over one engine, follows the book and the colour, and destroys the web view on leaving`() {
        val engines = mutableListOf<ReaderEngine>()
        var book by mutableStateOf<BookContent?>(null)
        var colour by mutableStateOf(Color.Black)
        var shown by mutableStateOf(true)
        compose.setContent {
            if (shown) ReaderWebView(book, onEngine = { engines += it }, background = colour)
        }
        compose.waitForIdle()
        val views = webViews()

        assertThat(engines).hasSize(1)
        assertThat(engines[0].bookBase.value).isNull()
        assertThat(shadowOf(views.single()).backgroundColor).isEqualTo(AndroidColor.BLACK)

        book = testBook(folder.newFile())
        colour = Color.White
        compose.waitForIdle()
        val first = engines[0].bookBase.value
        book = testBook(folder.newFile())
        compose.waitForIdle()

        assertThat(engines).hasSize(1)
        assertThat(webViews()).containsExactly(views.single())
        assertThat(first).isNotNull()
        assertThat(engines[0].bookBase.value).isNotEqualTo(first)
        assertThat(shadowOf(views.single()).backgroundColor).isEqualTo(AndroidColor.WHITE)

        shown = false
        compose.waitForIdle()

        assertThat(webViews()).isEmpty()
        assertThat(shadowOf(views.single()).wasDestroyCalled()).isTrue()
        assertThat(engines[0].bookBase.value).isNull()
    }
}

@RunWith(AndroidJUnit4::class)
class SystemWebViewTest {

    @Test
    fun `the major version is the first number of the package's version name`() {
        assertThat(webViewMajorVersion("131.0.6778.135")).isEqualTo(131)
        assertThat(webViewMajorVersion("120")).isEqualTo(120)
        assertThat(webViewMajorVersion(" 119.0.1 ")).isEqualTo(119)
        assertThat(webViewMajorVersion("beta-1")).isNull()
        assertThat(webViewMajorVersion("")).isNull()
        assertThat(webViewMajorVersion(null)).isNull()
    }

    @Test
    fun `it is read from the system's web view package`() {
        ShadowWebView.setCurrentWebViewPackage(PackageInfo().apply { versionName = "118.0.5993.80" })

        val version = systemWebViewMajorVersion(context())

        assertThat(version).isEqualTo(118)
        assertThat(checkNotNull(version) < MinimumWebViewMajorVersion).isTrue()
        assertThat(MinimumWebViewMajorVersion).isEqualTo(120)
    }

    @Test
    fun `no web view package is no version`() {
        ShadowWebView.setCurrentWebViewPackage(null)

        assertThat(systemWebViewMajorVersion(context())).isNull()
    }
}
