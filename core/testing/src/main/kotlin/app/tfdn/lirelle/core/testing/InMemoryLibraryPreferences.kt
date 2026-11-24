package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.library.ViewPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class InMemoryLibraryPreferences : LibraryPreferences {
    private val views = MutableStateFlow<Map<String, ViewPreferences>>(emptyMap())

    fun current(key: String): ViewPreferences = views.value[key] ?: ViewPreferences()

    override fun view(key: String): Flow<ViewPreferences> = views.map { it[key] ?: ViewPreferences() }

    override suspend fun update(key: String, transform: (ViewPreferences) -> ViewPreferences) {
        views.update { it + (key to transform(it[key] ?: ViewPreferences())) }
    }
}
