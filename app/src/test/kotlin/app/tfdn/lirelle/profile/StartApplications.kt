package app.tfdn.lirelle.profile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.LirelleApplication
import app.tfdn.lirelle.core.session.KeystoreTokenCipher
import app.tfdn.lirelle.signout.installFakeAndroidKeystore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * The application of a device that holds an installation of the version before the profiles when it starts:
 * the old files are written before anything of the application runs — before its objects are even injected —
 * as an update finds them. A test names one of these as its application (`@Config(application = …)`).
 */
internal abstract class StartingOnOldInstallation : LirelleApplication() {

    override fun onCreate() {
        installFakeAndroidKeystore()
        runBlocking { prepare(OldInstallation(this@StartingOnOldInstallation, KeystoreTokenCipher())) }
        super.onCreate()
    }

    /** Writes what the device holds before the start. */
    protected open suspend fun prepare(old: OldInstallation) = old.write()
}

/** An old installation, signed in, and nothing in the way of its migration. */
internal class OldInstallationApplication : StartingOnOldInstallation()

/**
 * An old installation whose migration cannot make the profiles' folder: a file is where it goes, so that the
 * first move fails as it does on a device with no room left.
 */
internal class BlockedFoldersApplication : StartingOnOldInstallation() {
    override suspend fun prepare(old: OldInstallation) {
        old.write()
        blocker(this).writeText("in the way")
    }

    companion object {
        fun blocker(application: LirelleApplication) = File(application.filesDir, "profiles")
    }
}

/**
 * An old installation one of whose files is held open when the migration comes to it: what a store opened too
 * early would do, and a failure that is no I/O error.
 */
internal class HeldFileApplication : StartingOnOldInstallation() {
    override suspend fun prepare(old: OldInstallation) {
        old.write()
        HeldFile.hold(preferencesDataStoreFile("library"))
    }
}

/**
 * An application whose profiles cannot be opened at its first start, for a reason nobody foresaw: an object
 * of the profile that cannot be built, a registry that throws what is no I/O error. The next opening goes well.
 */
internal class FailingOpenApplication : LirelleApplication() {
    private var failed = false

    override fun onCreate() {
        installFakeAndroidKeystore()
        super.onCreate()
    }

    override fun openGraphs(scope: CoroutineScope) {
        if (!failed) {
            failed = true
            error("/data/user/0/secret cannot be opened")
        }
        super.openGraphs(scope)
    }
}

/** A preferences file held open, as by a store of the application, until [release]. */
internal object HeldFile {
    private var holder: CoroutineScope? = null

    suspend fun hold(file: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Held from the first read on.
        PreferenceDataStoreFactory.create(scope = scope) { file }.data.first()
        holder = scope
    }

    suspend fun release() {
        holder?.coroutineContext?.job?.cancelAndJoin()
        holder = null
    }
}
