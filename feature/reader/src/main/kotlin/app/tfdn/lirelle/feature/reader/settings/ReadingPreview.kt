package app.tfdn.lirelle.feature.reader.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.feature.reader.FooterPreviewTag
import app.tfdn.lirelle.feature.reader.HeaderPreviewTag
import app.tfdn.lirelle.feature.reader.PreviewLine
import app.tfdn.lirelle.feature.reader.R
import app.tfdn.lirelle.feature.reader.colours
import app.tfdn.lirelle.feature.reader.label
import java.text.NumberFormat

/** The preview, for tests. */
const val ReadingPreviewTag = "reading-preview"

/** How many lines of the sample are shown: enough to see the text's look, the rest is cut. */
private const val SampleLines = 6

/** The room at the sides of the header and footer lines, and above and below the whole. */
private val Inset = 12.dp

/**
 * A paragraph drawn as a book would be with these settings, between a header and a footer line: the reading
 * theme's colours, the font, the size, the line spacing, the margins, the alignment, the hyphenation, and
 * what the four ends of the header and the footer show.
 *
 * It is drawn by the app, not by the reading engine, and a book has its own say: it is an approximation, which
 * the page says under it. It tells its theme and its text size to a screen reader, which cannot see them.
 *
 * @param scrolls whether its content scrolls inside the card when the card is given less height than it needs:
 * nothing of it is then out of reach. Only for a card whose height is bounded.
 */
@Composable
internal fun ReadingPreview(settings: ReaderSettings, modifier: Modifier = Modifier, scrolls: Boolean = false) {
    val colours = settings.theme.colours(systemDark = isSystemInDarkTheme())
    val context = LocalContext.current
    val families = remember(context) { PreviewFonts.families(context) }
    val size = FontSizesPx[settings.fontSizeStep.coerceIn(FontSizesPx.indices)]
    val state = stringResource(
        R.string.reader_preview_state,
        stringResource(settings.theme.label),
        NumberFormat.getInstance(LocalConfiguration.current.locales[0]).format(size),
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ReadingPreviewTag)
            .semantics { stateDescription = state },
        shape = MaterialTheme.shapes.medium,
        color = colours.background,
        contentColor = colours.foreground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        val scroll = if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(scroll.padding(vertical = Inset), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(horizontal = Inset)) {
                PreviewLine(settings.header, R.string.reader_running_preview_header, HeaderPreviewTag)
            }
            Text(
                text = stringResource(R.string.reader_preview_text),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = settings.margins.atTheSides),
                color = colours.foreground,
                overflow = TextOverflow.Ellipsis,
                maxLines = SampleLines,
                style = settings.sampleStyle(families),
            )
            Box(Modifier.padding(horizontal = Inset)) {
                PreviewLine(settings.footer, R.string.reader_running_preview_footer, FooterPreviewTag)
            }
        }
    }
}

/**
 * How the sample is written. The sizes are the page's CSS pixels, taken as scaled pixels: like the page, the
 * sample grows with the system's text size.
 */
private fun ReaderSettings.sampleStyle(families: Map<ReaderFont, FontFamily>): TextStyle = TextStyle(
    fontFamily = families[font] ?: FontFamily.Serif,
    fontSize = FontSizesPx[fontSizeStep.coerceIn(FontSizesPx.indices)].sp,
    lineHeight = LineHeights[lineHeightStep.coerceIn(LineHeights.indices)].em,
    // A book left to its own layout is shown starting at the start, as most are.
    textAlign = if (align == ReaderAlign.Justify) TextAlign.Justify else TextAlign.Start,
    hyphens = if (hyphenate) Hyphens.Auto else Hyphens.None,
)

/** The room the margins leave at each side of the sample: smaller than a page's, in proportion. */
private val MarginSize.atTheSides: Dp
    get() = when (this) {
        MarginSize.Narrow -> 8.dp
        MarginSize.Medium -> 16.dp
        MarginSize.Wide -> 28.dp
    }
