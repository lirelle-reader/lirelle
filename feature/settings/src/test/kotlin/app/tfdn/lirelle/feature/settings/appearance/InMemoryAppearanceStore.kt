package app.tfdn.lirelle.feature.settings.appearance

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A store kept in memory, for the tests of what reads and writes the theme. */
class InMemoryAppearanceStore(theme: AppTheme = AppTheme.System) : AppearanceStore {

    private val state = MutableStateFlow(theme)

    val stored: AppTheme get() = state.value

    /** False makes the writes fail, as a disk that cannot be written does: the theme stays as it was. */
    var writable = true

    override val theme: Flow<AppTheme> = state

    override suspend fun setTheme(theme: AppTheme): Boolean {
        if (writable) state.value = theme
        return writable
    }
}
