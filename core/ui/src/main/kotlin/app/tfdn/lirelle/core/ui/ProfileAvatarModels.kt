package app.tfdn.lirelle.core.ui

import androidx.compose.ui.graphics.Color
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
fun Profile.avatarModel(picture: File?): ProfileAvatarModel =
    profileAvatarModel(id, initialOf(session.user.username), kind, picture, signInNeeded)

/**
 * What the avatar draws of a profile of [kind], whose badge is the kind's: its letter, on its colours. For who has
 * no profile to map: a preview, a test.
 */
fun profileAvatarModel(
    seed: String,
    initial: String,
    kind: ServerKind,
    picture: File?,
    signInNeeded: Boolean,
): ProfileAvatarModel = ProfileAvatarModel(
    seed = seed,
    initial = initial,
    kindLetter = kind.letter,
    badgeGround = kind.badge.ground,
    badgeContent = kind.badge.content,
    picture = picture,
    signInNeeded = signInNeeded,
)

/** The first letter of [username], upper case, whole even when it is written with two code units. */
internal fun initialOf(username: String): String {
    val name = username.trim()
    if (name.isEmpty()) return NoInitial
    return name.substring(0, name.offsetByCodePoints(0, 1)).uppercase()
}

/**
 * The letter that stands for a kind of server on a profile's avatar. Komga and Kavita share theirs: the colours of
 * the badge tell them apart ([badge]).
 */
private val ServerKind.letter: String
    get() = when (this) {
        ServerKind.BookOrbit -> "B"
        ServerKind.Komga, ServerKind.Kavita -> "K"
    }

private val BookOrbitBadge = AvatarColors(ground = Color(0xFFFAFAF8), content = Color(0xFF1E1E18))
private val KomgaBadge = AvatarColors(ground = Color(0xFF005ED3), content = Color(0xFFFFFFFF))
private val KavitaBadge = AvatarColors(ground = Color(0xFF4AC694), content = Color(0xFF06281B))

/**
 * The colours of the badge of a kind of server, the same in the light and in the dark theme: each letter reads at
 * 4.5:1 or better on its ground.
 */
private val ServerKind.badge: AvatarColors
    get() = when (this) {
        ServerKind.BookOrbit -> BookOrbitBadge
        ServerKind.Komga -> KomgaBadge
        ServerKind.Kavita -> KavitaBadge
    }
