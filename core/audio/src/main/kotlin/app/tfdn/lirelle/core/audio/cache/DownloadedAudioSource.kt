package app.tfdn.lirelle.core.audio.cache

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import app.tfdn.lirelle.core.library.offline.DownloadedFiles

/**
 * Reads the downloaded file of an address when the device keeps one, and asks [streamed] (the cache, then the
 * server) for any other. A downloaded file is read where the request says, for as long as it says: a seek, or a
 * segment of a narration track, reads its own bytes, and nothing is asked of the network.
 *
 * @param downloaded looked at for each address opened, on the player's loading thread.
 */
@UnstableApi
class DownloadedAudioSource(
    private val downloaded: DownloadedFiles,
    private val streamed: DataSource,
) : DataSource {
    private val kept = FileDataSource()

    /** The one opened, until it is closed. */
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        kept.addTransferListener(transferListener)
        streamed.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val file = downloaded.audio(dataSpec.uri.toString())
        // The same place and length, in the file: only the address changes.
        val spec = file?.let { dataSpec.buildUpon().setUri(Uri.fromFile(it)).build() } ?: dataSpec
        val source = if (file != null) kept else streamed
        current = source
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(current) { "Read before it was opened" }.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        val source = current ?: return
        current = null
        source.close()
    }
}
