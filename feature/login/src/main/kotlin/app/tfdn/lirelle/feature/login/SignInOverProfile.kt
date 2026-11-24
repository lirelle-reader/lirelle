package app.tfdn.lirelle.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

private val BarHeight = 56.dp
private val BarPadding = 4.dp
private val LabelPadding = 8.dp

/**
 * Tells the sign-in step in front that the screens shown over a profile are being left, at the moment the user
 * turns back, not when the screens have gone: the step lives until then, and a sign-in answered meanwhile
 * would still be saved ([onLeft]).
 */
internal class SignInLeaving {
    private var step: (() -> Unit)? = null

    /** [onLeft] is what the step in front does when the screens are left. */
    fun listen(onLeft: () -> Unit) {
        step = onLeft
    }

    /** The step that listened with [onLeft] is no longer the one in front. */
    fun forget(onLeft: () -> Unit) {
        if (step === onLeft) step = null
    }

    fun tell() {
        step?.invoke()
    }
}

/** How the sign-in step hears that the screens it is shown in are left; null when not shown over a profile. */
internal val LocalSignInLeaving = staticCompositionLocalOf<SignInLeaving?> { null }

/**
 * The sign-in screens shown over the current profile: a bar that returns to it, and the steps under it. The
 * arrow and the system's Back both return to the profile, from either step, with nothing changed — a sign-in
 * in flight is ended ([SignInLeaving]); a sign-in that leaves the same profile current returns to it by itself.
 *
 * @param purpose adding a profile, or signing in again to one; never [SignInPurpose.First], which has no
 *   profile to return to.
 * @param onLeave returns to the profile: asked more than once, it leaves once.
 * @param avatar the avatar of the current profile, in the bar between the arrow and the profile's name: the
 *   app draws it, with the picture the device keeps of the account. Decoration, as the name is beside it.
 * @param steps the sign-in steps, given the profile that signs in again, if any, and what to tell once a
 *   sign-in has succeeded.
 */
@Composable
fun SignInOverProfile(
    purpose: SignInPurpose,
    onLeave: () -> Unit,
    viewModel: SignInOverProfileViewModel = signInOverProfileViewModel(purpose),
    avatar: @Composable () -> Unit = {},
    steps: @Composable (again: ProfileLabel?, onSignedIn: () -> Unit) -> Unit,
) {
    val leave by rememberUpdatedState(onLeave)
    LaunchedEffect(viewModel) { viewModel.left.collect { leave() } }
    val leaving = remember { SignInLeaving() }
    // The user turns back: the step is told first, so that nothing it has in flight is saved after that.
    val back = {
        leaving.tell()
        leave()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // The steps pad themselves for what is left: the bottom, and the keyboard.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        BackToProfileBar(current = viewModel.state.current, onBack = back, avatar = avatar)
        Box(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(LocalSignInLeaving provides leaving) {
                steps(viewModel.state.again, viewModel::onSignedIn)
            }
        }
    }
    // The one handler of the system's Back for these screens. After the steps: registered last, it is asked
    // before their own navigation, so that Back leaves the sign-in from the second step too rather than going
    // back to the server's address; and before what holds the screens, from their first frame on.
    BackHandler(onBack = back)
}

@Composable
private fun signInOverProfileViewModel(purpose: SignInPurpose): SignInOverProfileViewModel =
    hiltViewModel<SignInOverProfileViewModel, SignInOverProfileViewModel.Factory>(
        creationCallback = { it.create(purpose) },
    )

/**
 * The bar of the sign-in screens shown over a profile: the arrow that returns to [current], described by the
 * words shown beside it. A screen reader says them once, on the arrow.
 *
 * @param avatar the avatar of [current], between the arrow and the words.
 */
@Composable
internal fun BackToProfileBar(current: ProfileLabel?, onBack: () -> Unit, avatar: @Composable () -> Unit = {}) {
    val label = current?.let { stringResource(R.string.sign_in_back_to_profile, it.text()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BarHeight)
            .padding(horizontal = BarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = label ?: stringResource(R.string.sign_in_back),
            )
        }
        if (label != null) {
            avatar()
            Text(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = LabelPadding)
                    .semantics { hideFromAccessibility() },
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}
