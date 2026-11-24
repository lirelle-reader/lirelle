package app.tfdn.lirelle.core.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * [bytes] as the platform writes a file size, in the language of the app: "412 MB", "1.08 GB", "1,08 Go". From
 * a gigabyte the platform's longer form is used, with its two decimals: a tenth of a gigabyte is too coarse for
 * the size of a book.
 */
@Composable
fun fileSize(bytes: Long): String = LocalContext.current.fileSize(bytes)

/** The same, where there is a context and no composition. */
fun Context.fileSize(bytes: Long): String =
    if (bytes >= Gigabyte) Formatter.formatFileSize(this, bytes) else Formatter.formatShortFileSize(this, bytes)

/** As the platform counts it: in thousands. */
private const val Gigabyte = 1_000_000_000L
