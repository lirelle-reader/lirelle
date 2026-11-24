package app.tfdn.lirelle.core.network

import okhttp3.HttpUrl

/**
 * Where the picture of a profile's account is: the account's `avatarUrl` is written as its server writes it,
 * which the profile's source alone reads. Bound in the profile component.
 */
fun interface AccountPictures {
    /**
     * The absolute address of the picture [avatarUrl] names on [server], the profile's address; null when it names
     * none the source would fetch there. Whoever fetches it still checks that the address is on [server]: the
     * request carries the profile's token.
     */
    fun pictureUrl(server: String, avatarUrl: String): HttpUrl?
}
