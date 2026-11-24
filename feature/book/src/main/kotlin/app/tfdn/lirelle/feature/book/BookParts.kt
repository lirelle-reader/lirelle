package app.tfdn.lirelle.feature.book

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The least height of a heading that takes a tap. */
private val SectionLinkMinHeight = 48.dp

/**
 * A block with a heading.
 *
 * @param onOpenAll opens what the block shows a part of: its heading is then a line that takes the tap.
 *   Null for a block that leads nowhere, whose heading is a plain text.
 * @param openAllLabel what the tap on the heading does, said by a screen reader after the heading.
 */
@Composable
internal fun Section(
    @StringRes title: Int,
    tag: String,
    onOpenAll: (() -> Unit)? = null,
    @StringRes openAllLabel: Int? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onOpenAll == null) {
            Text(
                text = stringResource(title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            SectionLink(title, "$tag-open", openAllLabel?.let { stringResource(it) }, onOpenAll)
        }
        content()
    }
}

/** A heading that opens something: as wide as its block, its text at the start and a chevron at the end. */
@Composable
private fun SectionLink(@StringRes title: Int, tag: String, clickLabel: String?, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SectionLinkMinHeight)
            .testTag(tag)
            .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onOpen)
            .semantics { heading() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
    }
}

/** A name and its value; the value wraps under the name when the two do not fit on a line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Fact(@StringRes name: Int, value: String) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
    ) {
        Text(
            text = stringResource(name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A rounded label that is not a button. Its text wraps rather than being cut. */
@Composable
internal fun Label(
    text: String,
    modifier: Modifier = Modifier,
    ground: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    small: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(8.dp), color = ground, contentColor = ink) {
        Row(
            modifier = Modifier.padding(horizontal = if (small) 6.dp else 10.dp, vertical = if (small) 2.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            Text(text, style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge)
        }
    }
}

private val GenreLinkPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

/**
 * A genre, which opens the books of that genre.
 *
 * Outlined and in the colour of a link, to tell it from a [Label]. It is not a slot of a fixed
 * size: it is as wide as its name up to the width of the page, then its name wraps and it grows
 * taller, so a long genre is never cut. Around it, its touch target is at least 48 dp each way.
 */
@Composable
internal fun GenreLink(genre: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.genre_open_description, genre)
    Surface(
        onClick = onOpen,
        modifier = modifier
            .testTag("genre-$genre")
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(genre, modifier = Modifier.padding(GenreLinkPadding), style = MaterialTheme.typography.labelLarge)
    }
}
