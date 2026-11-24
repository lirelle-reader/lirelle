package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.reader.settings.ReaderSettings

/** Changes the reading settings: the transform is applied to what is stored when it is handled. */
internal typealias SettingsChange = ((ReaderSettings) -> ReaderSettings) -> Unit
