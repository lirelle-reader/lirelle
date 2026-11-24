package app.tfdn.lirelle.core.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import app.tfdn.lirelle.core.session.ServerKind

/**
 * The name of a kind of server, in words. The one place it is given: the sign-in screens, the profile button
 * and the list of profiles all name a profile's kind from here.
 */
@get:StringRes
val ServerKind.nameRes: Int
    get() = when (this) {
        ServerKind.BookOrbit -> R.string.server_kind_bookorbit
        ServerKind.Komga -> R.string.server_kind_komga
    }

/**
 * What is said of a profile of this kind whose server no longer takes its sign-in, "%1$s" being the profile as
 * [profileLine] names it: a session expired for BookOrbit, a key no longer valid for Komga, whose keys never expire.
 */
@get:StringRes
val ServerKind.signInNeededMessageRes: Int
    get() = when (this) {
        ServerKind.BookOrbit -> R.string.sign_in_needed_message
        ServerKind.Komga -> R.string.sign_in_needed_key_message
    }

/** A profile as it is named wherever it is listed: "`<username>` · `<kind>`", the kind of server in words. */
fun profileLine(resources: Resources, username: String, kind: ServerKind): String =
    resources.getString(R.string.profile_label, username, resources.getString(kind.nameRes))
