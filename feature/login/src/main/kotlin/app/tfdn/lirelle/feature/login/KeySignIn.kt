package app.tfdn.lirelle.feature.login

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.LoginOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The sign-in with a key the user made on the server and pasted ([LoginOptions.keyEntry]), of the sign-in step
 * whose [state] it shares: the same progress and the same end as a password sign-in — to the server and the kind
 * the step signs in to. What went wrong with it has its own place ([SignInUiState.keyError]), on the key's field:
 * the key's block is last on the screen, far from where the other sign-ins say it.
 *
 * The key is held in [state] only, in memory, as the password is: it is not saved with the screen, a process
 * stopped loses it, and it is forgotten once the server took it.
 *
 * @param signedIn told once the sign-in has succeeded and its session is saved: it forgets the secrets
 *   ([SignInUiState.signedIn]).
 */
class KeySignIn internal constructor(
    private val auth: AuthRepository,
    private val state: MutableStateFlow<SignInUiState>,
    private val scope: CoroutineScope,
    private val signedIn: suspend () -> Unit,
) {
    fun onChange(key: String) {
        state.update { it.copy(key = key, error = null, keyError = null) }
    }

    /** Sends the key as typed: the server's kind tidies what was pasted, and refuses a key it cannot be. */
    fun signIn() {
        val kind = state.value.kind
        // Also reached from the keyboard, which does not know the button is disabled.
        if (!state.value.canSubmitKey || kind == null) return
        state.update { it.starting() }
        scope.launch {
            val current = state.value
            when (val result = auth.signInWithKey(current.serverUrl, kind, current.key)) {
                is ApiResult.Success -> signedIn()
                is ApiResult.Failure -> state.update { it.copy(isBusy = false, keyError = result.error) }
            }
        }
    }
}
