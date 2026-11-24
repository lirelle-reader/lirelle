package app.tfdn.lirelle

import app.tfdn.lirelle.feature.settings.appearance.AppearanceStore
import app.tfdn.lirelle.feature.settings.appearance.NightMode
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Gives the theme stored to the system when the application starts. The system remembers the night mode of an
 * application by itself, but not across a restore or a new install, and it cannot be asked what it holds:
 * saying it again each time costs nothing and keeps the two in agreement.
 */
class AppThemeAtStart @Inject constructor(
    private val store: AppearanceStore,
    private val nightMode: NightMode,
) {

    /** Applies the theme stored, once. A theme chosen later is applied by the screen that changes it. */
    fun start(scope: CoroutineScope): Job = scope.launch { nightMode.apply(store.theme.first()) }
}
