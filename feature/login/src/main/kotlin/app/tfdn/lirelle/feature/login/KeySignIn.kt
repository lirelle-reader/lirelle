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
 * whose [state] it shares: the same progress, the same place for what went wrong, the same end as a password
 * sign-in — to the server and the kind the step signs in to.
 *
 * The key is held in [state] only, in memory, as the password is: it is not saved with the screen, a process
 * stopped loses it, and it is forgotten once the server took it.
 *
 * @param signedIn told once the sign-in has succeeded and its session is saved.
 */
class KeySignIn internal constructor(
    private val auth: AuthRepository,
    private val state: MutableStateFlow<SignInUiState>,
    private val scope: CoroutineScope,
    private val signedIn: suspend () -> Unit,
) {
    fun onChange(key: String) {
        state.update { it.copy(key = key, error = null) }
    }

    /** Sends the key as typed: the server's kind tidies what was pasted, and refuses a key it cannot be. */
    fun signIn() {
        val kind = state.value.kind
        // Also reached from the keyboard, which does not know the button is disabled.
        if (!state.value.canSubmitKey || kind == null) return
        state.update { it.copy(isBusy = true, error = null) }
        scope.launch {
            val current = state.value
            val result = auth.signInWithKey(current.serverUrl, kind, current.key)
            state.update {
                when (result) {
                    // Neither the key nor a password typed beside it is needed any more. The form stays busy
                    // until the screen leaves, as after a password ([SignInViewModel.onSignIn]).
                    is ApiResult.Success -> it.copy(password = "", key = "")
                    is ApiResult.Failure -> it.copy(isBusy = false, error = result.error)
                }
            }
            if (result is ApiResult.Success) signedIn()
        }
    }
}
