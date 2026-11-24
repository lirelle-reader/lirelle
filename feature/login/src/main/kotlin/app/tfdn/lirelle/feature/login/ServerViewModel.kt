package app.tfdn.lirelle.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.ServerAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ServerError {
    data object InvalidAddress : ServerError
    data class Api(val error: ApiError) : ServerError
}

data class ServerUiState(
    val address: String = "",
    val isChecking: Boolean = false,
    val error: ServerError? = null,
    /** Set while the user must confirm connecting to this `http://` address. */
    val cleartextAddress: String? = null,
)

/**
 * The server step of a sign-in: the address, offered from the one the device remembers and remembered once it
 * was checked. About no profile: it is the same whether nobody is signed in or a profile is added over the
 * current one, which it never looks at.
 */
@HiltViewModel
class ServerViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        ServerUiState(address = auth.serverUrl.value.orEmpty()),
    )
    val state: StateFlow<ServerUiState> = mutableState.asStateFlow()

    private val connectedChannel = Channel<Unit>(Channel.BUFFERED)

    /** Emits once each time a server was checked and remembered. */
    val connected: Flow<Unit> = connectedChannel.receiveAsFlow()

    fun onAddressChange(address: String) {
        mutableState.update { it.copy(address = address, error = null) }
    }

    fun onContinue() {
        if (state.value.isChecking) return
        when (val parsed = ServerAddress.parse(state.value.address)) {
            ServerAddress.Invalid -> mutableState.update { it.copy(error = ServerError.InvalidAddress) }
            is ServerAddress.Valid ->
                if (parsed.isCleartext) {
                    mutableState.update { it.copy(cleartextAddress = parsed.url, error = null) }
                } else {
                    connect(parsed.url)
                }
        }
    }

    fun onCleartextConfirmed() {
        val address = state.value.cleartextAddress ?: return
        mutableState.update { it.copy(cleartextAddress = null) }
        connect(address)
    }

    fun onCleartextDismissed() {
        mutableState.update { it.copy(cleartextAddress = null) }
    }

    private fun connect(serverUrl: String) {
        mutableState.update { it.copy(isChecking = true, error = null) }
        viewModelScope.launch {
            when (val result = auth.connectToServer(serverUrl)) {
                is ApiResult.Success -> {
                    mutableState.update { it.copy(isChecking = false) }
                    connectedChannel.send(Unit)
                }
                is ApiResult.Failure ->
                    mutableState.update { it.copy(isChecking = false, error = ServerError.Api(result.error)) }
            }
        }
    }
}
