package app.tfdn.lirelle.source.bookorbit.network.testing

import app.tfdn.lirelle.core.session.PendingOidcRequest
import app.tfdn.lirelle.core.session.PendingOidcStore

class InMemoryPendingOidcStore : PendingOidcStore {
    var pending: PendingOidcRequest? = null

    override suspend fun save(request: PendingOidcRequest) {
        pending = request
    }

    override suspend fun takeWhen(meant: (PendingOidcRequest) -> Boolean): PendingOidcRequest? =
        pending?.takeIf(meant)?.also { pending = null }

    override suspend fun peek(): PendingOidcRequest? = pending

    override suspend fun isPending(): Boolean = pending != null

    override suspend fun clear() {
        pending = null
    }
}
