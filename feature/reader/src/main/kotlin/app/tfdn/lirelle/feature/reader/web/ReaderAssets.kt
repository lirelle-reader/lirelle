package app.tfdn.lirelle.feature.reader.web

import android.content.res.AssetManager
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/** The only host the reader's web view may talk to; nothing behind it is on the network. */
internal const val ReaderHost = WebViewAssetLoader.DEFAULT_DOMAIN

internal const val ReaderOrigin = "https://$ReaderHost"

internal const val ReaderPageUrl = "$ReaderOrigin/reader/index.html"

/**
 * Sent with every response of the origin; the page repeats it in a `<meta>`. Without `blob:` or `'unsafe-inline'`
 * for scripts, nothing a book contains can run, in the page or in the frames that show the book.
 */
internal const val ContentSecurityPolicy = "default-src 'none'; script-src 'self'; " +
    "style-src 'self' blob: 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self' blob: data:; " +
    "media-src blob:; connect-src 'self'; frame-src blob:; object-src 'none'; base-uri 'none'; form-action 'none'"

internal const val Utf8 = "utf-8"
internal const val JsonMediaType = "application/json"
internal const val BinaryMediaType = "application/octet-stream"

private val AssetMediaTypes = mapOf(
    "html" to "text/html",
    "js" to "text/javascript",
    "css" to "text/css",
    "json" to JsonMediaType,
    "woff2" to "font/woff2",
)

private val TextMediaTypes = setOf("text/html", "text/javascript", "text/css", JsonMediaType)

private val Headers = mapOf(
    "Content-Security-Policy" to ContentSecurityPolicy,
    "X-Content-Type-Options" to "nosniff",
    "Cache-Control" to "no-store",
)

internal fun assetMediaType(path: String): String {
    val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
    return AssetMediaTypes[extension.lowercase()] ?: BinaryMediaType
}

/** A 200 of the reader's origin. [encoding] is null for what declares its own or has none. */
internal fun served(mediaType: String, encoding: String?, data: InputStream) =
    WebResourceResponse(mediaType, encoding, HttpURLConnection.HTTP_OK, "OK", Headers, data)

/** For what the reader's origin does not have: said outright, so that the page's `fetch` sees a plain failure. */
internal fun notFound() = empty(HttpURLConnection.HTTP_NOT_FOUND, "Not Found")

/** For everything that is not the reader's origin. */
internal fun refused() = empty(HttpURLConnection.HTTP_FORBIDDEN, "Forbidden")

private fun empty(status: Int, reason: String) =
    WebResourceResponse("text/plain", Utf8, status, reason, Headers, ByteArrayInputStream(ByteArray(0)))

/** A relative path made of real names only: no `..`, no `.`, no empty segment, no backslash. */
internal fun isPlainPath(path: String): Boolean =
    '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

/** Serves the files of one folder of the app's assets, and nothing above it. */
internal class AssetFolderHandler(
    private val assets: AssetManager,
    private val folder: String,
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse {
        if (!isPlainPath(path)) return notFound()
        val data = try {
            assets.open("$folder/$path")
        } catch (_: IOException) {
            // Not there, or a folder.
            return notFound()
        }
        val mediaType = assetMediaType(path)
        return served(mediaType, Utf8.takeIf { mediaType in TextMediaTypes }, data)
    }
}
