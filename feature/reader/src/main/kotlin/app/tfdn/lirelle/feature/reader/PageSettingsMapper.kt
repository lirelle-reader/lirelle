package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.feature.reader.bridge.PageAlign
import app.tfdn.lirelle.feature.reader.bridge.PageBrightnessEdge
import app.tfdn.lirelle.feature.reader.bridge.PageFlow
import app.tfdn.lirelle.feature.reader.bridge.PageFont
import app.tfdn.lirelle.feature.reader.bridge.PageNoteLabels
import app.tfdn.lirelle.feature.reader.bridge.PageSettings

/** The margins offered, as percentages of the page's width. */
private const val NarrowMarginPercent = 4.0
private const val MediumMarginPercent = 7.0
private const val WideMarginPercent = 12.0

private const val OnePage = 1
private const val TwoPages = 2

/** The note panel's texts until the screen has given those of the app's language. */
val EnglishNoteLabels = PageNoteLabels("Go to the note", "Note")

/**
 * What the page is told to draw with: these settings in the colours of the resolved theme.
 *
 * @param twoPages the window has room for two pages side by side.
 * @param noteLabels the note panel's texts, in the language of the app.
 */
fun ReaderSettings.toPageSettings(
    colours: ThemeColours,
    twoPages: Boolean,
    noteLabels: PageNoteLabels,
): PageSettings = PageSettings(
    background = colours.background.toCssHex(),
    foreground = colours.foreground.toCssHex(),
    link = colours.link.toCssHex(),
    dark = colours.isDark,
    font = font.page(),
    fontSizePx = FontSizesPx[fontSizeStep.coerceIn(FontSizesPx.indices)],
    lineHeight = LineHeights[lineHeightStep.coerceIn(LineHeights.indices)],
    marginPercent = margins.percent(),
    align = align.page(),
    hyphenate = hyphenate,
    flow = flow.page(),
    maxColumns = if (twoPages) TwoPages else OnePage,
    brightnessEdge = brightnessEdge.page(),
    highlightBlend = colours.highlightBlend,
    highlightOpacity = colours.highlightOpacity,
    searchColor = colours.searchColour.toCssHex(),
    footnotePanel = footnotePanel,
    noteLabels = noteLabels,
)

/** Whether the page lays the book out again for [other]: what the user's place on a page depends on. */
internal fun PageSettings.layoutDiffers(other: PageSettings): Boolean =
    font != other.font || fontSizePx != other.fontSizePx || lineHeight != other.lineHeight ||
        marginPercent != other.marginPercent || align != other.align || hyphenate != other.hyphenate ||
        flow != other.flow || maxColumns != other.maxColumns

private fun ReaderFont.page() = when (this) {
    ReaderFont.Book -> PageFont.Book
    ReaderFont.Serif -> PageFont.Serif
    ReaderFont.Sans -> PageFont.Sans
    ReaderFont.Dyslexic -> PageFont.Dyslexic
}

private fun MarginSize.percent() = when (this) {
    MarginSize.Narrow -> NarrowMarginPercent
    MarginSize.Medium -> MediumMarginPercent
    MarginSize.Wide -> WideMarginPercent
}

private fun ReaderAlign.page() = when (this) {
    ReaderAlign.Book -> PageAlign.Book
    ReaderAlign.Justify -> PageAlign.Justify
    ReaderAlign.Start -> PageAlign.Start
}

private fun ReaderFlow.page() = when (this) {
    ReaderFlow.Paginated -> PageFlow.Paginated
    ReaderFlow.Scrolled -> PageFlow.Scrolled
}

private fun BrightnessEdge.page() = when (this) {
    BrightnessEdge.Left -> PageBrightnessEdge.Left
    BrightnessEdge.Right -> PageBrightnessEdge.Right
    BrightnessEdge.Off -> PageBrightnessEdge.Off
}
