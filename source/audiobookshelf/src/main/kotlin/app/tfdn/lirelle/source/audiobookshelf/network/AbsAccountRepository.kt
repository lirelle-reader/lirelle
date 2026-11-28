package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession

/**
 * An Audiobookshelf profile's account, read with its access token (`GET api/me`): named by its username, its id a
 * UUID, whether it may download from its permissions. `api/me` tells no version. Writes through [session], the
 * profile's own: an answer that comes after the profile closed changes that profile or nothing.
 *
 * Read with the profile's client, whose authorizer renews the token, and marks the profile when the server refuses
 * to renew it.
 */
internal class AbsAccountRepository(
    private val session: ProfileSession,
    private val api: AbsApis<AbsAccountApi>,
    private val errors: AbsErrors,
) : AccountRepository {

    override suspend fun account(): ApiResult<AccountInfo> = errors.call(AbsCall.Authenticated) {
        val serverUrl = session.session.value.serverUrl
        val user = api.forServer(serverUrl).me().toProfile()
        session.updateUser(user)
        AccountInfo(
            name = user.name,
            username = user.username,
            email = user.email,
            serverUrl = serverUrl,
            serverVersion = null,
        )
    }

    override suspend fun refreshAccount(): ApiResult<Unit> = errors.call(AbsCall.Authenticated) {
        session.updateUser(api.forServer(session.session.value.serverUrl).me().toProfile())
    }
}
