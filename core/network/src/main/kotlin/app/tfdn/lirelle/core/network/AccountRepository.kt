package app.tfdn.lirelle.core.network

/** @property serverVersion null when the server tells none to this account: Komga tells it to administrators only. */
data class AccountInfo(
    val name: String,
    val username: String,
    val email: String?,
    val serverUrl: String,
    val serverVersion: String?,
)

/**
 * What one profile asks its server about its own account. Apart from [AuthRepository], which works without a
 * profile: this one speaks with the profile's tokens and writes what it learns to that profile only.
 */
interface AccountRepository {

    /** Reads the account for the Account screen, and stores what changed like [refreshAccount]. */
    suspend fun account(): ApiResult<AccountInfo>

    /** Re-reads the account and stores what changed (the user's details, their permissions). */
    suspend fun refreshAccount(): ApiResult<Unit>
}
