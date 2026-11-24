package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderSettingsStore
import app.tfdn.lirelle.core.reader.settings.normalized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * [ReaderSettingsStore] kept in memory, clamping as the real one does. [gate], when set, holds
 * every write until it is completed, as a slow disk would.
 */
class InMemoryReaderSettingsStore(initial: ReaderSettings = ReaderSettings()) : ReaderSettingsStore {
    private val state = MutableStateFlow(initial.normalized())

    /** What is stored now. */
    val current: ReaderSettings get() = state.value

    /** How many writes were asked for. */
    var writes = 0
        private set

    var gate: CompletableDeferred<Unit>? = null

    override val settings: Flow<ReaderSettings> = state

    override suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        writes++
        gate?.await()
        state.update { transform(it).normalized() }
    }
}
