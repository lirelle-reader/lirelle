package app.tfdn.lirelle.feature.reader.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.normalized
import app.tfdn.lirelle.feature.reader.R
import app.tfdn.lirelle.feature.reader.SettingsChange

/** Stands for the view model under the page: it applies each change to [settings], as the store does. */
internal class PageHost(initial: ReaderSettings? = ReaderSettings()) {
    var settings by mutableStateOf(initial)

    val change: SettingsChange = { transform -> settings = settings?.let { transform(it).normalized() } }

    /** What is stored, which a test of a change expects to be there. */
    val stored: ReaderSettings get() = checkNotNull(settings)
}

internal fun string(@StringRes id: Int, vararg arguments: Any): String =
    ApplicationProvider.getApplicationContext<Context>().getString(id, *arguments)

internal fun SemanticsNodeInteractionsProvider.preview(): SemanticsNodeInteraction = onNodeWithTag(ReadingPreviewTag)

/** What the preview says of itself: the reading theme and the text size. */
internal fun SemanticsNodeInteractionsProvider.previewState(): String? =
    preview().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

/** The preview's paragraph as it was laid out: its style, its lines. */
internal fun SemanticsNodeInteractionsProvider.sampleLayout(): TextLayoutResult {
    val node = onNodeWithText(string(R.string.reader_preview_text), useUnmergedTree = true).fetchSemanticsNode()
    val results = mutableListOf<TextLayoutResult>()
    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    return results.single()
}

/** Where the node is, whatever is shown of it. */
internal fun SemanticsNodeInteraction.bounds(): Rect =
    fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
