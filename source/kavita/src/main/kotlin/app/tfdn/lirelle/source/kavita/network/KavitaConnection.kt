package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.AccountPictures
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Kavita gives an account no picture the app shows: the profile's avatar is its initial. */
internal object KavitaAccountPictures : AccountPictures {
    override fun pictureUrl(server: String, avatarUrl: String): HttpUrl? = null
}

/**
 * Kavita's long transfers, none until its files are read (#51): every request goes on with the client's own
 * patience.
 */
internal object PassThrough : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
