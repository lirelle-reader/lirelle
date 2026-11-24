package app.tfdn.lirelle.di

import android.content.Context
import android.content.res.Resources
import androidx.annotation.RawRes
import app.tfdn.lirelle.R
import app.tfdn.lirelle.feature.settings.about.Licences
import app.tfdn.lirelle.feature.settings.about.LicencesSource
import app.tfdn.lirelle.feature.settings.about.appComponent
import app.tfdn.lirelle.feature.settings.about.licencesOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The list of what the app ships, read from the raw resource the build writes, and the app's own licence.
 *
 * A resource that is missing or cannot be read gives empty lists, or a licence without its text: the licences'
 * screens then show no component, or the licence's name alone, and nothing fails.
 *
 * @param resource the raw resource holding the list; the one generated at build time unless a test gives another.
 * @param appLicence the raw resource holding the app's licence, a copy of the repository's `LICENSE`.
 */
class LicencesInResources(
    private val resources: Resources,
    @param:RawRes private val resource: Int = R.raw.aboutlibraries,
    @param:RawRes private val appLicence: Int = R.raw.gpl_3_0_or_later,
) : LicencesSource {

    override suspend fun load(): Licences = withContext(Dispatchers.IO) {
        val json = readOrNull(resource)
        val lists = if (json == null) Licences(bundled = emptyList(), libraries = emptyList()) else licencesOf(json)
        lists.copy(app = appComponent(licenceText = readOrNull(appLicence)))
    }

    private fun readOrNull(@RawRes id: Int): String? = try {
        resources.openRawResource(id).bufferedReader().use { it.readText() }
    } catch (_: Resources.NotFoundException) {
        null
    } catch (_: IOException) {
        null
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LicencesModule {
    @Provides
    fun licencesSource(@ApplicationContext context: Context): LicencesSource = LicencesInResources(context.resources)
}
