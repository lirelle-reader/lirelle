package app.tfdn.lirelle.core.downloads

import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.library.net.NetworkState
import kotlinx.coroutines.flow.Flow

/**
 * What surrounds the downloads asked from a page: the profile's network, whether the account may download, the
 * download settings, and the space left on the device, in bytes.
 */
class DownloadSurroundings(
    val network: Flow<NetworkState>,
    val canDownload: Flow<Boolean>,
    val settings: DownloadSettingsStore,
    val freeBytes: () -> Long,
)
