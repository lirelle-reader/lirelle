package app.tfdn.lirelle.core.downloads

import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.network.ApiError
import okhttp3.Response

/**
 * What the profile's server means when it refuses a kept file: its source reads the answer, as it reads those of
 * its other calls. A failure of the network is no refusal: the server was not reached, or did not answer whole.
 */
fun interface DownloadRefusals {
    /**
     * The failure the answer [response], which is no success, says. Its body may be peeked at; it is not to be
     * consumed, nor the answer closed.
     */
    fun refusal(response: Response): ApiError

    /**
     * How the download fails on the answer [response], as [refusal] says: the server's refusal, unless the source
     * knows it for the account's rights ([DownloadFailure.NotAllowed]). The same care as [refusal] for the answer.
     */
    fun failure(response: Response): DownloadFailure = DownloadFailure.Server(refusal(response))
}
