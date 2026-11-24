package app.tfdn.lirelle.feature.reader.di

import android.content.Context
import app.tfdn.lirelle.feature.reader.CheckedEpubOpener
import app.tfdn.lirelle.feature.reader.EpubOpener
import app.tfdn.lirelle.feature.reader.WebViewVersion
import app.tfdn.lirelle.feature.reader.text.DeviceTextActions
import app.tfdn.lirelle.feature.reader.text.TextActions
import app.tfdn.lirelle.feature.reader.web.systemWebViewMajorVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers

/** What the reader takes from the device, whoever is signed in. */
@Module
@InstallIn(SingletonComponent::class)
internal object ReaderFeatureModule {

    @Provides
    fun webViewVersion(@ApplicationContext context: Context): WebViewVersion =
        WebViewVersion { systemWebViewMajorVersion(context) }

    /** Reading and checking a file is done off the main thread. */
    @Provides
    fun epubOpener(): EpubOpener = CheckedEpubOpener(Dispatchers.IO)

    @Provides
    fun textActions(@ApplicationContext context: Context): TextActions = DeviceTextActions(context)
}
