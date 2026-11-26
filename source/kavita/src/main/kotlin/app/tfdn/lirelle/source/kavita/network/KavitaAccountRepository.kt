package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession

/**
 * A Kavita profile's account, read with its key: named by its username, its id the text of its number, with the
 * version the server runs when it tells one. Writes through [session], the profile's own: an answer that comes
 * after the profile closed changes that profile or nothing.
 *
 * It is also how a key the server no longer takes is noticed while the library asks nothing: the account is read
 * again whenever the server may answer, with the profile's client, whose authorizer marks the profile.
 */
internal class KavitaAccountRepository(
    private val session: ProfileSession,
    private val api: KavitaApis<KavitaAccountApi>,
    private val errors: KavitaErrors,
) : AccountRepository {

    override suspend fun account(): ApiResult<AccountInfo> = errors.call(KavitaCall.Authenticated) {
        val serverUrl = session.session.value.serverUrl
        val account = api.forServer(serverUrl).account().answer()
        val user = account.toProfile()
        session.updateUser(user)
        AccountInfo(
            name = user.name,
            username = user.username,
            email = user.email,
            serverUrl = serverUrl,
            serverVersion = account.kavitaVersion?.ifBlank { null },
        )
    }

    override suspend fun refreshAccount(): ApiResult<Unit> = errors.call(KavitaCall.Authenticated) {
        session.updateUser(api.forServer(session.session.value.serverUrl).account().answer().toProfile())
    }
}
