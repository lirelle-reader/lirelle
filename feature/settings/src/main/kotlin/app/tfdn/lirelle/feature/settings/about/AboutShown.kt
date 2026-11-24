package app.tfdn.lirelle.feature.settings.about

import androidx.compose.runtime.saveable.Saver

private const val AboutName = "about"
private const val LicencesName = "licences"
private const val TextPrefix = "text:"

/** What About shows: itself, the list of licences reached from it, or the text of a component's licences. */
internal sealed interface AboutShown {
    data object About : AboutShown

    data object Licences : AboutShown

    data class Text(val componentId: String) : AboutShown
}

/** Keeps what is shown as a short text, which holds through a restart of the app. */
internal val AboutShownSaver: Saver<AboutShown, String> = Saver(
    save = { shown ->
        when (shown) {
            AboutShown.About -> AboutName
            AboutShown.Licences -> LicencesName
            is AboutShown.Text -> TextPrefix + shown.componentId
        }
    },
    restore = { saved ->
        when {
            saved == LicencesName -> AboutShown.Licences
            saved.startsWith(TextPrefix) -> AboutShown.Text(saved.removePrefix(TextPrefix))
            else -> AboutShown.About
        }
    },
)
