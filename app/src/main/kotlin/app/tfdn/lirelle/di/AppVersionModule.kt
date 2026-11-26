package app.tfdn.lirelle.di

import app.tfdn.lirelle.BuildConfig
import app.tfdn.lirelle.core.network.di.AppVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The app's version name, as the build wrote it: the only place it enters the network layer (its user agent). */
@Module
@InstallIn(SingletonComponent::class)
object AppVersionModule {
    @Provides
    @AppVersion
    fun appVersion(): String = BuildConfig.VERSION_NAME
}
