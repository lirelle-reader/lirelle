package app.tfdn.lirelle.feature.reader.web

import android.content.Context
import androidx.webkit.WebViewCompat

/**
 * The oldest system web view the reader's engine works on (grouping functions, CSS nesting and container
 * queries, see the research notes on foliate-js). Below it, ask for an update instead of opening the reader.
 */
const val MinimumWebViewMajorVersion = 120

/** The major version of the system's web view, null when there is none or its version cannot be read. */
fun systemWebViewMajorVersion(context: Context): Int? =
    webViewMajorVersion(WebViewCompat.getCurrentWebViewPackage(context)?.versionName)

internal fun webViewMajorVersion(versionName: String?): Int? =
    versionName?.trim()?.substringBefore('.')?.toIntOrNull()
