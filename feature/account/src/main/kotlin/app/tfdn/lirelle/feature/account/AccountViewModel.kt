package app.tfdn.lirelle.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.session.profile.ProfileSignIn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

sealed interface AccountUiState {
    data object Loading : AccountUiState
    data class Loaded(val info: AccountInfo) : AccountUiState
    data class Failed(val error: ApiError) : AccountUiState
}

/**
 * The question asked before the open profile is removed.
 *
 * @property profileId the profile it was asked about: the one this was asked in, whoever is current when it
 *   is shown or answered.
 * @property cost what removing it deletes, and what it would lose.
 */
data class RemovalQuestion(val profileId: String, val cost: RemovalCost.Counted)

/**
 * The current profile's server-side details and its removal: what the Profiles page asks of the open profile.
 * (It keeps the name of the Account page that page replaced.) The removal is a sign-out. The list of profiles
 * is the device's ([ProfilesPageViewModel]).
 *
 * It is made in the open profile and lives as long as it: the profile it speaks of is always [signIn]'s, never
 * "whoever is current".
 *
 * @param auth what removes the profile: it signs it out, telling its server, and ends the profile.
 * @param account the account of the open profile, as its server knows it.
 * @param books what the device keeps or is fetching: the removal deletes it.
 * @param pending what the removal would lose.
 * @param signIn whose profile this is, and whether its session waits for a sign-in.
 */
@HiltViewModel
class AccountViewModel internal constructor(
    private val auth: AuthRepository,
    private val account: AccountRepository,
    private val books: Flow<List<DownloadedBook>>,
    private val pending: PendingWrites,
    private val signIn: ProfileSignIn,
) : ViewModel() {

    @Inject
    constructor(
        auth: AuthRepository,
        account: AccountRepository,
        downloads: Downloads,
        pending: PendingWrites,
        signIn: ProfileSignIn,
    ) : this(auth, account, downloads.books, pending, signIn)

    private val mutableState = MutableStateFlow<AccountUiState>(AccountUiState.Loading)
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()

    private val mutableQuestion = MutableStateFlow<RemovalQuestion?>(null)

    /** Null until the user asks to remove the profile, and again once they answered. */
    val removalQuestion: StateFlow<RemovalQuestion?> = mutableQuestion.asStateFlow()

    private var loading: Job? = null

    init {
        load()
        viewModelScope.launch { loadAgainOnceSignedIn() }
    }

    /**
     * The server is asked again each time the profile, which waited for a sign-in, no longer does: what was
     * answered while the session was refused says nothing any more, and the page offered nothing to ask again
     * meanwhile. Followed here, not by the page: the sign-in screens are in front of it when the mark falls,
     * and it is not there to see it. The mark, not the network coming back: when it falls the session already
     * carries the new tokens ([app.tfdn.lirelle.core.session.profile.ProfileSession.signInNeeded]), and a
     * device without a network at that moment gets an error that offers to try again.
     *
     * Nothing is asked for a profile that never waited — the load of the start is its own — nor twice for one
     * sign-in.
     */
    private suspend fun loadAgainOnceSignedIn() {
        var waited = false
        signIn.needed.collect { needed ->
            if (waited && !needed) load()
            waited = needed
        }
    }

    /**
     * Loads the account from its server. Also the retry action. One answer at a time: the one of a load asked
     * before is not waited for any more, and cannot come after and replace this one's.
     */
    fun load() {
        loading?.cancel()
        mutableState.value = AccountUiState.Loading
        loading = viewModelScope.launch {
            mutableState.value = when (val result = account.account()) {
                is ApiResult.Success -> AccountUiState.Loaded(result.value)
                is ApiResult.Failure -> AccountUiState.Failed(result.error)
            }
        }
    }

    /**
     * Nothing is done yet: the user is told what removing the profile deletes, and what it would lose. The
     * question names this profile, so that it is neither shown nor answered for another.
     */
    fun onAskRemove() {
        viewModelScope.launch {
            val kept = books.first()
            val cost = RemovalCost.Counted(kept.size, kept.sumOf { it.bytesOnDevice }, pending.atRisk())
            mutableQuestion.value = RemovalQuestion(signIn.profileId, cost)
        }
    }

    fun onDismissRemove() {
        mutableQuestion.value = null
    }

    /**
     * The user confirmed. The answer is taken once: a second tap, before the question has left the screen,
     * finds none. The sign-out names the profile the question was asked about, and does nothing when another
     * one is current by then ([AuthRepository.signOut]): nobody else is removed, whatever happened between
     * the question and the answer.
     */
    fun onRemove() {
        val question = mutableQuestion.getAndUpdate { null } ?: return
        viewModelScope.launch { auth.signOut(question.profileId) }
    }
}
