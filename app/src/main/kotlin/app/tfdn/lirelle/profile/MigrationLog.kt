package app.tfdn.lirelle.profile

import android.util.Log
import app.tfdn.lirelle.core.session.kindAndFrames

/** The tag of what the profiles tell logcat. */
private const val Tag = "Profiles"

/**
 * Tells logcat that something of the migration went wrong, without what the failure itself says: its message
 * may quote a path, a server's address, an account or a title, and a log leaves the device with a bug report.
 * What is told of it is its kind and where it came from ([kindAndFrames]).
 *
 * @param message what could not be done, in words of the code: a name of the application's own at most.
 */
internal fun warnOfMigration(message: String, failure: Throwable) {
    Log.w(Tag, "$message: ${failure.kindAndFrames()}")
}
