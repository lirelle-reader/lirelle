package app.tfdn.lirelle.core.network

import okhttp3.OkHttpClient

/**
 * The client without credentials — sign-in, renewal, anything public — and the one every other client of the app
 * is made from, with `newBuilder()`, so that what it sets reaches every request: the [UserAgent] first.
 *
 * The only `OkHttpClient.Builder()` of the app: `scripts/check-single-http-client.sh` refuses any other in CI.
 */
fun newPublicClient(agent: UserAgent): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(UserAgentInterceptor(agent))
        .build()
