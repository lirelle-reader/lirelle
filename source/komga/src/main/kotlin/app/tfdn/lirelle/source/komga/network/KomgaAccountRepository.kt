package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession

/**
 * A Komga profile's account, read with its key: named by its email, which is its username and its name. Komga tells
 * its version to administrators only, so none is given. Writes through [session], the profile's own: an answer that
 * comes after the profile closed changes that profile or nothing.
 */
internal class KomgaAccountRepository(
    private val session: ProfileSession,
    private val api: KomgaApis<KomgaAccountApi>,
    private val errors: KomgaErrors,
) : AccountRepository {

    override suspend fun account(): ApiResult<AccountInfo> = errors.call(KomgaCall.Authenticated) {
        val serverUrl = session.session.value.serverUrl
        val user = api.forServer(serverUrl).account().toProfile()
        session.updateUser(user)
        AccountInfo(user.name, user.username, user.email, serverUrl = serverUrl, serverVersion = null)
    }

    override suspend fun refreshAccount(): ApiResult<Unit> = errors.call(KomgaCall.Authenticated) {
        session.updateUser(api.forServer(session.session.value.serverUrl).account().toProfile())
    }
}
