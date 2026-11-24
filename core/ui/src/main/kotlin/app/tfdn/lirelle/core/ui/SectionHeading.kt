package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

const val SeeAllTag = "see-all"

private val HeadingMinHeight = 48.dp

/**
 * The title of a section, with an optional button at its end, "See all" unless told otherwise.
 *
 * The button goes under the title when both do not fit on one line, so neither is squeezed.
 *
 * @param seeAllLabel the words on the button.
 * @param onSeeAll what the button does; null for a heading without a button.
 * @param seeAllDescription what a screen reader says for the button; by default "See all" and
 *   the title, so that the button says which section it belongs to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    seeAllLabel: String = stringResource(R.string.row_see_all),
    onSeeAll: (() -> Unit)? = null,
    seeAllDescription: String? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier
                .heightIn(min = HeadingMinHeight)
                .wrapContentHeight()
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        if (onSeeAll != null) {
            val description = seeAllDescription ?: stringResource(R.string.row_see_all_description, title)
            TextButton(
                onClick = onSeeAll,
                modifier = Modifier
                    .testTag(SeeAllTag)
                    .semantics { contentDescription = description },
            ) { SingleLineLabel(seeAllLabel) }
        }
    }
}
