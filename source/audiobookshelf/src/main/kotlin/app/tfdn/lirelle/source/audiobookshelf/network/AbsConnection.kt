package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.AccountPictures
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** An Audiobookshelf account has no picture the app shows: the profile's avatar is its initial. */
internal object AbsAccountPictures : AccountPictures {
    override fun pictureUrl(server: String, avatarUrl: String): HttpUrl? = null
}

/**
 * Audiobookshelf's long transfers, none until its books are read and listened to (#68, #69): every request goes on
 * with the client's own patience.
 */
internal object AbsPassThrough : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
