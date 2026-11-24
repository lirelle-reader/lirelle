package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.library.RecentSearches
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** [RecentSearches] kept in memory: newest first, one entry per text. It does not cap the list. */
class InMemoryRecentSearches(initial: List<String> = emptyList()) : RecentSearches {
    private val state = MutableStateFlow(initial)

    override val entries: Flow<List<String>> = state

    override suspend fun record(text: String) {
        val clean = text.trim()
        if (clean.isNotEmpty()) state.update { list -> listOf(clean) + list.filterNot { it.equals(clean, true) } }
    }

    override suspend fun remove(text: String) = state.update { list -> list - text }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
