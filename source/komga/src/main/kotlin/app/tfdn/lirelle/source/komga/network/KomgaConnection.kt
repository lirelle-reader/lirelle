package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.AccountPictures
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Komga gives an account no picture: the profile's avatar is its initial. */
internal object KomgaAccountPictures : AccountPictures {
    override fun pictureUrl(server: String, avatarUrl: String): HttpUrl? = null
}

/**
 * Komga's long transfers, none until its files are read (3b): every request goes on with the client's own
 * patience.
 */
internal object PassThrough : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
