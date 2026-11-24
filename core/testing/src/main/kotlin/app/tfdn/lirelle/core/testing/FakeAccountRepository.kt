package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.network.AccountInfo
import app.tfdn.lirelle.core.network.AccountRepository
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

/** Scriptable [AccountRepository]. Set the `…Result` fields, then read [calls]. */
class FakeAccountRepository : AccountRepository {
    var accountResult: ApiResult<AccountInfo> = ApiResult.Success(
        AccountInfo("Ann Reader", "reader", "ann@example.com", "https://books.example.com", "v3.8.0"),
    )

    var refreshAccountResult: ApiResult<Unit> = ApiResult.Success(Unit)

    /**
     * When set, every call is recorded, then waits for this before answering. Lets a test
     * look at the state while a call is in flight, then let it finish with `complete(Unit)`.
     */
    var gate: CompletableDeferred<Unit>? = null

    /** One entry per call: `account` or `refreshAccount`. */
    val calls = mutableListOf<String>()

    override suspend fun account(): ApiResult<AccountInfo> {
        calls += "account"
        gate?.await()
        return accountResult
    }

    override suspend fun refreshAccount(): ApiResult<Unit> {
        calls += "refreshAccount"
        gate?.await()
        return refreshAccountResult
    }
}
