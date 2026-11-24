package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.store.DownloadFolders
import java.io.File
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val PartSuffix = ".part"

private val KeptJson = Json { ignoreUnknownKeys = true }

private val File.part: File get() = File(parentFile, name + PartSuffix)

/**
 * Whole or not at all: written beside the file, then moved onto it. What cannot be written is only not kept,
 * and nothing is once the profile closed: [folders] makes the file's folder, or refuses to.
 */
private fun File.replaceWith(folders: DownloadFolders, write: (File) -> Boolean): Boolean {
    val part = part
    val written = try {
        folders.make(parentFile) && write(part) && part.renameTo(this)
    } catch (_: IOException) {
        false
    }
    if (!written) part.delete()
    return written
}

internal fun <T> File.writeJson(folders: DownloadFolders, serializer: KSerializer<T>, value: T): Boolean =
    replaceWith(folders) { part ->
        part.writeText(KeptJson.encodeToString(serializer, value))
        true
    }

/** Null when there is none, or when it no longer reads. */
internal fun <T> File.readJson(serializer: KSerializer<T>): T? = try {
    if (isFile) KeptJson.decodeFromString(serializer, readText()) else null
} catch (_: IOException) {
    null
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/** Fetches [url] into [target]; when it cannot be, [target] stays as it was. */
internal fun OkHttpClient.fetchInto(url: String, target: File, folders: DownloadFolders): Boolean =
    target.replaceWith(folders) { part ->
        val request = try {
            Request.Builder().url(url).build()
        } catch (_: IllegalArgumentException) {
            return@replaceWith false
        }
        newCall(request).execute().use { response ->
            if (response.isSuccessful) part.outputStream().use { response.body.byteStream().copyTo(it) }
            response.isSuccessful
        }
    }

/** Keeps the image at [url] in [target], as [fetchInto]; a book that has no such image ([url] is null) keeps none. */
internal fun OkHttpClient.keepImage(url: String?, target: File, folders: DownloadFolders) {
    if (url == null) target.delete() else fetchInto(url, target, folders)
}

/** With what an interrupted write left beside it. */
internal fun File.deleteKept() {
    delete()
    part.delete()
}
