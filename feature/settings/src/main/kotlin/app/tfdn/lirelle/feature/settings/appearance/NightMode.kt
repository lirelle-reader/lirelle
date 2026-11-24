package app.tfdn.lirelle.feature.settings.appearance

import android.app.UiModeManager

/** Gives a theme to the system, which then reports the app as light or dark everywhere. */
fun interface NightMode {
    fun apply(theme: AppTheme)
}

/**
 * The system keeps a night mode for each application. It cannot be read back: the store is what says which
 * theme was chosen.
 */
internal class SystemNightMode(private val uiModeManager: UiModeManager) : NightMode {

    override fun apply(theme: AppTheme) {
        uiModeManager.setApplicationNightMode(
            when (theme) {
                AppTheme.System -> UiModeManager.MODE_NIGHT_AUTO
                AppTheme.Light -> UiModeManager.MODE_NIGHT_NO
                AppTheme.Dark -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }
}
