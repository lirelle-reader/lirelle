package app.tfdn.lirelle.feature.login

/**
 * Keeps track of the OIDC sign-in the browser is open for, and sees that exactly one outcome
 * is applied to it.
 *
 * The answer of the browser has two ways back, and which one is in play is told by the route
 * in [browserOpened]:
 * - an Auth Tab answers with an activity result, so coming back to the screen means nothing;
 * - a browser without Auth Tab shows a plain Custom Tab, whose redirect comes through the
 *   activity's intent filter, so coming back to the screen without one means the user closed
 *   the tab ([closedTabMeansCancelled]).
 *
 * Both ways end in [settle], which lets the first outcome through and drops whatever arrives
 * while it is applied or after it. Meant for the main thread only.
 *
 * [hasPendingRequest] tells whether a request waits on disk: it outlives the screen when the
 * app is killed while the browser is in front.
 */
internal class OidcTurn(private val hasPendingRequest: suspend () -> Boolean) {
    private var awaitingAnswer = false
    private var authTabOpen = false
    private var settling = false

    /** True when coming back to the screen with no redirect means the user gave up. */
    val closedTabMeansCancelled: Boolean get() = awaitingAnswer && !authTabOpen

    /** Whether the browser was opened as an Auth Tab; null when no sign-in waits for an answer. */
    val openedInAuthTab: Boolean? get() = authTabOpen.takeIf { awaitingAnswer }

    /** The browser is about to be asked for. */
    fun begin() {
        awaitingAnswer = true
        authTabOpen = false
    }

    /** The browser is in front, as an Auth Tab or, when not [authTab], as a Custom Tab. */
    fun browserOpened(authTab: Boolean) {
        authTabOpen = authTab && awaitingAnswer
    }

    /** No answer is expected any more. */
    fun drop() {
        awaitingAnswer = false
        authTabOpen = false
    }

    /**
     * Runs [outcome] if a sign-in waits for one and no other outcome took it, and tells
     * whether it did. With [orLeftOnDisk], a request left by a previous process counts too.
     */
    suspend fun settle(orLeftOnDisk: Boolean = true, outcome: suspend () -> Unit): Boolean {
        if (settling) return false
        settling = true
        try {
            if (!awaitingAnswer && !(orLeftOnDisk && hasPendingRequest())) return false
            drop()
            outcome()
            return true
        } finally {
            settling = false
        }
    }
}
