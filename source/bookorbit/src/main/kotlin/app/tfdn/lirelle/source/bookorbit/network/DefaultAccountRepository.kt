package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession

/**
 * Writes through [session], the profile's own: an answer that comes after the profile closed changes that
 * profile or nothing, never the account that signed in meanwhile.
 */
internal class DefaultAccountRepository(
    private val session: ProfileSession,
    private val accountApi: ApiProvider<AccountApi>,
    private val errors: ErrorMapper,
) : AccountRepository {

    override suspend fun account(): ApiResult<AccountInfo> = errors.call(CallKind.Authenticated) {
        val serverUrl = session.session.value.serverUrl
        val api = accountApi.forServer(serverUrl)
        val me = api.me()
        session.updateUser(me.toProfile())
        AccountInfo(
            name = me.name,
            username = me.username,
            email = me.email,
            serverUrl = serverUrl,
            serverVersion = api.appInfo().version,
        )
    }

    override suspend fun refreshAccount(): ApiResult<Unit> = errors.call(CallKind.Authenticated) {
        session.updateUser(accountApi.forServer(session.session.value.serverUrl).me().toProfile())
    }
}
