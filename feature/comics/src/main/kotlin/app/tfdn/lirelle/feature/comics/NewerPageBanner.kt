package app.tfdn.lirelle.feature.comics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.SingleLineLabel

const val NewerPageBannerTag = "comic-newer-page"

/**
 * Another device read further: the page it is at, "Ignore" and "Go there". The buttons go under the sentence
 * when they do not fit beside it.
 *
 * @param page the number of that page, from 1.
 * @param insets what of the window's edges the banner keeps clear of: its bottom too when nothing is under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewerPageBanner(
    page: Int,
    actions: NewerPageActions,
    insets: WindowInsets,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(NewerPageBannerTag),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        FlowRow(
            modifier = Modifier.windowInsetsPadding(insets).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.comics_newer_page, page),
                modifier = Modifier.padding(vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = actions::ignoreNewer, modifier = Modifier.heightIn(min = MinTarget)) {
                SingleLineLabel(stringResource(R.string.comics_newer_page_ignore))
            }
            Button(onClick = actions::acceptNewer, modifier = Modifier.heightIn(min = MinTarget)) {
                SingleLineLabel(stringResource(R.string.comics_newer_page_go))
            }
        }
    }
}
