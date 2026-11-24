package app.tfdn.lirelle.di

import app.tfdn.lirelle.feature.settings.appearance.AppearanceStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * How the app looks on this device, as the application's own graph holds it. It is no account's, so it is kept
 * apart from [AccountDataOnDevice]. Only the test of what a sign-out leaves alone reads it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppearanceOnDevice {
    fun appearance(): AppearanceStore
}
