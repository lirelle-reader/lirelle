package app.tfdn.lirelle.core.ui

import app.tfdn.lirelle.core.session.Profile
import app.tfdn.lirelle.core.session.ServerKind
import java.io.File

/** Shown for an account whose username is empty: an avatar always has an initial. */
private const val NoInitial = "?"

/**
 * What the avatar draws of this profile ([ProfileAvatar]), with [picture], the picture the device keeps of its
 * account, if any. Apart from the avatar itself, which knows nothing of the registry: whoever lists profiles
 * — the profile button, the list, the Profiles page, the sign-in bar — maps them here, the same way.
 */
fun Profile.avatarModel(picture: File?): ProfileAvatarModel = ProfileAvatarModel(
    seed = id,
    initial = initialOf(session.user.username),
    kindLetter = kind.letter,
    picture = picture,
    signInNeeded = signInNeeded,
)

/** The first letter of [username], upper case, whole even when it is written with two code units. */
internal fun initialOf(username: String): String {
    val name = username.trim()
    if (name.isEmpty()) return NoInitial
    return name.substring(0, name.offsetByCodePoints(0, 1)).uppercase()
}

/** The letter that stands for a kind of server on a profile's avatar. */
private val ServerKind.letter: String
    get() = when (this) {
        ServerKind.BookOrbit -> "B"
        ServerKind.Komga -> "K"
    }
