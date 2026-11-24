package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

/** The margin at the sides of a page, which its title keeps too. */
private val Gutter = 16.dp

/** Where the title starts in a bar that has a back arrow. */
private val TitleStartAfterArrow = 56.dp

/**
 * The bar and the title of a settings page, and under them the area the page lays itself out in: the whole
 * width, the system's bars taken off, nothing scrolling. [SettingsPageFrame] is the usual page built on it.
 *
 * The title is never cut. It sits in the bar when it is whole there on one line. When it is not, a narrow
 * window with large text, the bar keeps only its arrow and the title is drawn under it on the width of the
 * page, wrapping between words, in a smaller style if one of its words would still be too long.
 *
 * @param onBack leaves the page; null inside a pane, where there is no arrow.
 */
@Composable
fun SettingsPageScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val headingStyle = headingStyleOrNull(title, hasArrow = onBack != null, width = maxWidth)
        Scaffold(
            topBar = {
                // With neither an arrow nor a title to hold, there is no bar.
                if (headingStyle == null || onBack != null) PageBar(title.takeIf { headingStyle == null }, onBack)
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (headingStyle != null) PageHeading(title, headingStyle, underBar = onBack != null)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    content = content,
                )
            }
        }
    }
}

/** The bar of a page: its back arrow where it has one, and its title when it fits there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(title: String?, onBack: (() -> Unit)?) {
    TopAppBar(
        title = { if (title != null) SingleLineLabel(text = title, modifier = Modifier.semantics { heading() }) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                    )
                }
            }
        },
    )
}

/** The title of a page drawn over its content, for a title the bar has no room for. */
@Composable
private fun PageHeading(title: String, style: TextStyle, underBar: Boolean) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Gutter, end = Gutter, top = if (underBar) 0.dp else Gutter, bottom = 8.dp)
            .semantics { heading() },
        style = style,
    )
}

/**
 * The style of the title drawn under the bar, or null when the title is whole in the bar.
 *
 * @param width the width of the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun headingStyleOrNull(title: String, hasArrow: Boolean, width: Dp): TextStyle? {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    // What the system's bars and a cutout take at the sides, which the bar keeps clear of.
    val insets = TopAppBarDefaults.windowInsets.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val room = width - insets.calculateStartPadding(direction) - insets.calculateEndPadding(direction)
    return remember(title, hasArrow, room, density, typography) {
        // One pixel more: the width was rounded.
        fun widthOf(text: String, style: TextStyle): Dp =
            with(density) { (measurer.measure(text, style, softWrap = false, maxLines = 1).size.width + 1).toDp() }

        val roomInBar = room - (if (hasArrow) TitleStartAfterArrow else Gutter) - Gutter
        if (widthOf(title, typography.titleLarge) <= roomInBar) {
            null
        } else {
            val words = title.split(Regex("\\s+")).filter(String::isNotEmpty)
            val styles = listOf(typography.titleLarge, typography.titleMedium, typography.titleSmall)
            // The first style in which the longest word is whole on a line of the page.
            styles.firstOrNull { style -> words.all { widthOf(it, style) <= room - Gutter * 2 } } ?: styles.last()
        }
    }
}
