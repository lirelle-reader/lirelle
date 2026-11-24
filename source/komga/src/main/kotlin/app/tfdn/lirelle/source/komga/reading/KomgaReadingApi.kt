package app.tfdn.lirelle.source.komga.reading

import app.tfdn.lirelle.source.komga.network.dto.BookDto
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * What a Komga profile reads and writes of its reading of a book, relative to the server's address, with its key.
 * The place is the book's read progress; it is written as a Readium progression, which Komga refuses (409) when it
 * holds a newer one. The answers whose status says something besides success are given whole.
 */
internal interface KomgaReadingApi {
    @GET("api/v1/books/{bookId}")
    suspend fun book(@Path("bookId") bookId: String): BookDto

    /** 204 when taken, 409 when Komga holds a place read later. */
    @PUT("api/v1/books/{bookId}/progression")
    suspend fun saveProgression(@Path("bookId") bookId: String, @Body progression: ProgressionDto): Response<Unit>

    /** The place Komga holds, with when it was read; no body (204) when the book was never opened. */
    @GET("api/v1/books/{bookId}/progression")
    suspend fun progression(@Path("bookId") bookId: String): Response<StoredProgressionDto>

    /** The series' next book; 404 after its last. */
    @GET("api/v1/books/{bookId}/next")
    suspend fun next(@Path("bookId") bookId: String): Response<BookDto>
}

/**
 * A place written to Komga (`R2Progression`): when the page was reached, by which device, and where. Every field is
 * sent, none having a default: Komga requires them all.
 */
@Serializable
internal data class ProgressionDto(val modified: String, val device: DeviceDto, val locator: LocatorDto)

/** The device a place was read on, as Komga keeps and shows it. */
@Serializable
internal data class DeviceDto(val id: String, val name: String)

/** Where in the book (`R2Locator`): the page's address and format. */
@Serializable
internal data class LocatorDto(val href: String, val type: String, val locations: LocationsDto)

/**
 * The page from 1 ([position], which Komga takes as the page of a comic) and how far into the book it is, from 0 to
 * 1. Komga requires [fragments], which a page has none of.
 */
@Serializable
internal data class LocationsDto(val fragments: List<String>, val position: Int, val totalProgression: Double)

/** What the app reads of the place Komga holds: when it was read, on which device, and at which page. */
@Serializable
internal data class StoredProgressionDto(
    val modified: String? = null,
    val device: StoredDeviceDto? = null,
    val locator: StoredLocatorDto? = null,
)

@Serializable
internal data class StoredDeviceDto(val id: String? = null)

@Serializable
internal data class StoredLocatorDto(val locations: StoredLocationsDto? = null)

/** The page from 1 of a comic's place. */
@Serializable
internal data class StoredLocationsDto(val position: Int? = null)
