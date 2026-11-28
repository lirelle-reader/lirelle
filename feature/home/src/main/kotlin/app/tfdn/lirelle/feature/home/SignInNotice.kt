package app.tfdn.lirelle.feature.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.WrappingLabel
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.core.ui.profileLine

const val SignInNoticeTag = "home-sign-in-notice"
const val SignInNoticeBodyTag = "home-sign-in-notice-body"
const val SignInAgainTag = "home-sign-in-again"
const val SignInOfflineBooksTag = "home-sign-in-offline-books"

private val NoticeShape = RoundedCornerShape(16.dp)
private val NoticePadding = 16.dp
private val NoticeGap = 12.dp
private val NoticeIconSize = 24.dp
private val NoticeTextGap = 4.dp

/** Wider than this, a sentence is too long a line to read and its button too far from it: a tablet's Home. */
private val NoticeMaxWidth = 600.dp

/** As tall as what a finger needs, and seen so: the one thing to do about the notice. */
private val NoticeButtonHeight = 48.dp

/** What happened to a profile's sign-in, as its kind says it: a session expired, or a key no longer valid. */
@get:StringRes
private val ServerKind.noticeBody: Int
    get() = when (this) {
        ServerKind.BookOrbit, ServerKind.Audiobookshelf -> R.string.home_sign_in_notice_body
        ServerKind.Komga, ServerKind.Kavita -> R.string.home_sign_in_notice_key_body
    }

/**
 * What Home says while its profile waits for a sign-in: the [SignInNotice] and, while Home has no row to show
 * ([offersOfflineBooks]), the way to the downloaded books under it, at its start.
 *
 * The notice says the downloaded books stay readable; with rows on screen their covers lead to them, but a
 * Home that never loaded has none, and its failure — whose screen offers that way — is not shown beside the
 * notice. So the same action is offered here, by the same words, less loud than "Sign in again": outlined.
 * Both keep to one width, so the action never stands further out than the card it belongs to.
 *
 * @param onOpenDownloads selects the Downloads destination, as the screen shown for lack of network does.
 */
@Composable
internal fun SignInNoticeBlock(
    expired: ExpiredSession,
    offersOfflineBooks: Boolean,
    onSignInAgain: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.widthIn(max = NoticeMaxWidth),
        verticalArrangement = Arrangement.spacedBy(NoticeGap),
    ) {
        SignInNotice(expired, onSignInAgain)
        if (offersOfflineBooks) {
            OutlinedButton(
                onClick = onOpenDownloads,
                modifier = Modifier
                    .heightIn(min = NoticeButtonHeight)
                    .testTag(SignInOfflineBooksTag),
            ) {
                WrappingLabel(stringResource(CoreR.string.no_network_open_downloads))
            }
        }
    }
}

/**
 * The notice of Home while the session of its profile has expired: what happened and to whom, what still
 * works meanwhile, and the way out — "Sign in again".
 *
 * It names the profile as it is named everywhere ("`<username>` · `<kind>`"): with several accounts on the
 * device, "your session" would not say which. It stands on the error container's colour; its button takes
 * that pair the other way round — the colour of the card's text as its ground, the card's colour as its
 * text —, which reads on the card in both themes with the very contrast the card's own text has.
 *
 * For a screen reader: the sentence is said when the notice appears, without cutting what was being said;
 * the title is a heading; the icon says nothing the title does not.
 *
 * @param onSignInAgain opens the sign-in screens for [expired]'s profile.
 */
@Composable
internal fun SignInNotice(expired: ExpiredSession, onSignInAgain: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val profile = profileLine(LocalResources.current, expired.username, expired.kind)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SignInNoticeTag),
        shape = NoticeShape,
        color = scheme.errorContainer,
        contentColor = scheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(NoticePadding), verticalArrangement = Arrangement.spacedBy(NoticeGap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(NoticeGap)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, modifier = Modifier.size(NoticeIconSize))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NoticeTextGap)) {
                    Text(
                        text = stringResource(CoreR.string.sign_in_needed),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(expired.kind.noticeBody, profile),
                        modifier = Modifier
                            .testTag(SignInNoticeBodyTag)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = onSignInAgain,
                modifier = Modifier
                    .align(Alignment.End)
                    .heightIn(min = NoticeButtonHeight)
                    .testTag(SignInAgainTag),
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.onErrorContainer,
                    contentColor = scheme.errorContainer,
                ),
            ) {
                WrappingLabel(stringResource(CoreR.string.sign_in_again))
            }
        }
    }
}
