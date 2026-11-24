package app.tfdn.lirelle.source.bookorbit.downloads

import app.tfdn.lirelle.core.downloads.DownloadRefusals
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import okhttp3.Response
import retrofit2.HttpException

/** No honest refusal of the server is longer; the rest of a longer one is not read. */
private const val MaxRefusalBytes = 64L * 1024

/**
 * A BookOrbit server's refusal of a kept file, read as the refusals of its other calls made with the session's
 * token: the answer is handed to the error mapper as the exception those calls throw.
 */
internal class BookOrbitDownloadRefusals(private val errors: ErrorMapper) : DownloadRefusals {
    override fun refusal(response: Response): ApiError {
        val refused = HttpException(retrofit2.Response.error<Any>(response.peekBody(MaxRefusalBytes), response))
        return errors.map(refused, CallKind.Authenticated)
    }
}
