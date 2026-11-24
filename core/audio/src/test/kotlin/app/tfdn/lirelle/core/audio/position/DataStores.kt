package app.tfdn.lirelle.core.audio.position

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

/** Real preference stores in a temporary folder, closed after the test. */
class DataStores : ExternalResource() {
    private val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun before() = folder.create()

    override fun after() {
        scope.cancel()
        folder.delete()
    }

    fun create(name: String = "listening"): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") }
}

/** A store whose file cannot be read or written. */
val BrokenDataStore = object : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("disk") }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        throw IOException("disk")
}
