package app.tfdn.lirelle.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.feature.account.switcher.ProfileRow

/** The column of the current profile, and the one of the other profiles, wherever they are placed. */
const val CurrentProfileColumnTag = "profiles-current-column"
const val OtherProfilesColumnTag = "profiles-others-column"

private val ColumnMaxWidth = 600.dp
private val TwoColumnsMaxWidth = 1040.dp
private val ColumnsGap = 32.dp

/**
 * What the Profiles page leads to.
 *
 * @property onRetry asks the current profile's server again.
 * @property onSwitch a profile that sleeps was chosen: it becomes the current one.
 * @property onAdd "Add a profile".
 * @property onSignInAgain "Sign in again", for the profile of that id: the current one, from its button, or
 *   one that sleeps, from its menu.
 * @property onRemoveCurrent "Remove this profile", of the current one: asks first.
 * @property onRemoveOther the same of the profile of that id, which sleeps.
 */
class ProfilesPageActions(
    val onBack: () -> Unit,
    val onRetry: () -> Unit,
    val onSwitch: (profileId: String) -> Unit,
    val onAdd: () -> Unit,
    val onSignInAgain: (profileId: String) -> Unit,
    val onRemoveCurrent: () -> Unit,
    val onRemoveOther: (profileId: String) -> Unit,
)

/**
 * More › Profiles, with its confirmation of a removal.
 *
 * Two view models, for the two sides of the page: the device's lists the profiles and removes one that sleeps;
 * the open profile's asks its server — again by itself once its account has signed in again — and removes the
 * current profile, which closes that profile, and this page with it: nothing here navigates then, the level
 * it is in is replaced as a whole.
 *
 * @param onAddProfile opens the sign-in screens to add a profile.
 * @param onSignInAgain opens them for the profile of that id to sign in again.
 */
@Composable
fun ProfilesRoute(
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onSignInAgain: (profileId: String) -> Unit,
    account: AccountViewModel = hiltViewModel(),
    profiles: ProfilesPageViewModel = hiltViewModel(),
) {
    val state by profiles.state.collectAsStateWithLifecycle()
    val details by account.state.collectAsStateWithLifecycle()
    ProfilesScreen(
        state = state,
        details = details,
        actions = ProfilesPageActions(
            onBack = onBack,
            onRetry = account::load,
            onSwitch = profiles::switchTo,
            onAdd = onAddProfile,
            onSignInAgain = onSignInAgain,
            onRemoveCurrent = account::onAskRemove,
            onRemoveOther = profiles::onAskRemove,
        ),
    )
    val question by account.removalQuestion.collectAsStateWithLifecycle()
    val asked by profiles.removal.collectAsStateWithLifecycle()
    val current = state.current?.row
    val sleeping = state.others.firstOrNull { it.id == asked }
    // Asked in the open profile, about itself: it is shown only while that profile is the current one.
    val counted = question?.takeIf { it.profileId == current?.id }
    DismissForAnother(question, current?.id, account::onDismissRemove)
    when {
        counted != null && current != null ->
            RemoveProfileDialog(current, counted.cost, account::onRemove, account::onDismissRemove)
        sleeping != null ->
            RemoveProfileDialog(sleeping, RemovalCost.NotCounted, profiles::onRemove, profiles::onDismissRemove)
    }
}

/**
 * The question about the open profile is taken back once another profile is the current one — a sign-in that
 * ended in the browser, a question asked late by a second tap: it is never shown beside the profile that took
 * its place, with numbers that are not that one's, and does not wait there for an answer.
 *
 * @param currentId the current profile as the registry has it; null while there is none.
 */
@Composable
private fun DismissForAnother(question: RemovalQuestion?, currentId: String?, dismiss: () -> Unit) {
    LaunchedEffect(question, currentId) {
        if (question != null && question.profileId != currentId) dismiss()
    }
}

/**
 * The Profiles page: the current profile with what is known of its account, the other profiles, and what adds
 * or removes one.
 *
 * One column up to medium widths. On an expanded one, with several profiles, two: the current profile at the
 * start, the others and "Add a profile" at the end. With one profile there is one column at every width, as
 * the Account page it replaces.
 *
 * @param details what the current profile's server says: only its version is taken from it, the rest of the
 *   account being what the registry keeps, which that answer has already updated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    state: ProfilesPageState,
    details: AccountUiState,
    actions: ProfilesPageActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.profiles_title), modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.account_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // None while the page is left with its profile: nothing is shown of the one that takes its place.
            val current = state.current ?: return@Box
            val several = state.others.isNotEmpty()
            val twoColumns = several && LocalWindowWidthClass.current == WindowWidthClass.Expanded
            val shown = CurrentShown(
                profile = current,
                details = details,
                several = several,
                serverFirst = several && !twoColumns,
                ownColumn = twoColumns,
            )
            when {
                !several -> OneProfile(shown, actions)
                twoColumns -> TwoColumns(shown, state.others, actions)
                else -> OneColumn(shown, state.others, actions)
            }
        }
    }
}

/** The current profile's section, with the page's two actions about it: asking its server again, signing in. */
@Composable
private fun CurrentProfile(shown: CurrentShown, actions: ProfilesPageActions) {
    CurrentProfileSection(shown, actions.onRetry, onSignInAgain = { actions.onSignInAgain(shown.profile.row.id) })
}

/** The only profile: today's page, with "Add a profile" before "Remove this profile". */
@Composable
private fun OneProfile(shown: CurrentShown, actions: ProfilesPageActions) {
    Column(
        modifier = Modifier
            .widthIn(max = ColumnMaxWidth)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .testTag(CurrentProfileColumnTag),
    ) {
        CurrentProfile(shown, actions)
        AddProfileButton(actions.onAdd, Modifier.padding(top = 16.dp))
        RemoveProfileButton(actions.onRemoveCurrent, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun OneColumn(shown: CurrentShown, others: List<ProfileRow>, actions: ProfilesPageActions) {
    Column(
        modifier = Modifier
            .widthIn(max = ColumnMaxWidth)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Column(Modifier.testTag(CurrentProfileColumnTag)) {
            CurrentProfile(shown, actions)
            RemoveProfileButton(actions.onRemoveCurrent, Modifier.padding(top = 8.dp, bottom = 12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(top = 8.dp).testTag(OtherProfilesColumnTag)) {
            OtherProfilesSection(others, actions)
            AddProfileButton(actions.onAdd, Modifier.padding(top = 12.dp))
        }
    }
}

/** Side by side, and scrolling together: neither column is a pane of its own. */
@Composable
private fun TwoColumns(shown: CurrentShown, others: List<ProfileRow>, actions: ProfilesPageActions) {
    Row(
        modifier = Modifier
            .widthIn(max = TwoColumnsMaxWidth)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(ColumnsGap),
    ) {
        Column(Modifier.weight(1f).testTag(CurrentProfileColumnTag)) {
            CurrentProfile(shown, actions)
            RemoveProfileButton(actions.onRemoveCurrent, Modifier.padding(top = 16.dp))
        }
        Column(Modifier.weight(1f).testTag(OtherProfilesColumnTag)) {
            OtherProfilesSection(others, actions)
            AddProfileButton(actions.onAdd, Modifier.padding(top = 16.dp))
        }
    }
}
