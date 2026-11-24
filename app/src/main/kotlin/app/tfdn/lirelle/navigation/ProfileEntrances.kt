package app.tfdn.lirelle.navigation

/**
 * The ways from the shell into the sign-in screens shown over the current profile, above the shell: for its
 * profile button, its Profiles page and the notice of a session that expired.
 *
 * @property addProfile opens them to add a profile.
 * @property signInAgain opens them for the profile of that id to sign in again, on its server.
 */
class ProfileEntrances(
    val addProfile: () -> Unit,
    val signInAgain: (profileId: String) -> Unit,
)
