package app.tfdn.lirelle.source.komga.testing

// Komga's answers about books, series and read lists, written by hand in the shape Komga 1.30.1 gives them (the
// fields checked on a real server, spec 3a2 "Why"), with invented titles and names. Each carries a field the app
// does not read, as Komga's answers grow.

/** A book half read: page 12 of 48, of a series, by a writer who is also its colourist. */
const val ReadingBookJson = """{"id":"0BK1A","seriesId":"0SR1A","seriesTitle":"Tidewater","libraryId":"0LB1",""" +
    """"name":"Tidewater 03","url":"Tidewater 03.cbz","number":3,"created":"2026-10-01T09:00:00Z",""" +
    """"lastModified":"2026-11-20T08:15:00Z","fileLastModified":"2026-09-30T18:00:00Z","sizeBytes":52428800,""" +
    """"size":"50 MiB","oneshot":false,"deleted":false,"fileHash":"a1b2c3",""" +
    """"media":{"status":"READY","mediaType":"application/zip","mediaProfile":"DIVINA","pagesCount":48,""" +
    """"comment":"","epubDivinaCompatible":false,"epubIsKepub":false},""" +
    """"metadata":{"title":"The Third Tide","titleLock":false,"summary":"A storm reaches the harbour.",""" +
    """"number":"3","numberSort":3.0,"releaseDate":"2019-04-12","authors":[{"name":"Writer One","role":"writer"},""" +
    """{"name":"Artist Two","role":"penciller"},{"name":"Writer One","role":"colorist"}],"tags":["sea"],""" +
    """"isbn":"9780000000001","links":[]},""" +
    """"readProgress":{"page":12,"completed":false,"readDate":"2026-11-21T20:30:00Z",""" +
    """"created":"2026-11-02T19:00:00Z","lastModified":"2026-11-21T20:30:00Z","deviceId":"","deviceName":""}}"""

/** A book never opened: no read progress, no summary, no ISBN, no release date. */
const val UnreadBookJson = """{"id":"0BK2B","seriesId":"0SR1A","seriesTitle":"Tidewater","libraryId":"0LB1",""" +
    """"name":"Tidewater 04","number":4,"created":"2026-10-02T09:00:00Z","lastModified":"2026-10-02T09:00:00Z",""" +
    """"oneshot":false,"deleted":false,""" +
    """"media":{"status":"READY","mediaType":"application/zip","mediaProfile":"DIVINA","pagesCount":52},""" +
    """"metadata":{"title":"","summary":"","number":"4","numberSort":4.0,"authors":[],"tags":[],"isbn":""},""" +
    """"readProgress":null}"""

/** A one-shot read to the end: a series of its own, which the app does not show as one. */
const val ReadOneShotJson = """{"id":"0BK3C","seriesId":"0SR2B","seriesTitle":"Lantern","libraryId":"0LB1",""" +
    """"name":"Lantern","number":1,"created":"2026-08-01T09:00:00Z","lastModified":"2026-08-03T09:00:00Z",""" +
    """"oneshot":true,"deleted":false,""" +
    """"media":{"status":"READY","mediaType":"application/pdf","mediaProfile":"PDF","pagesCount":30},""" +
    """"metadata":{"title":"Lantern","number":"1","numberSort":1.0,"authors":[{"name":"Writer Three",""" +
    """"role":"writer"}]},""" +
    """"readProgress":{"page":30,"completed":true,"readDate":"2026-11-01T22:00:00Z","lastModified":""" +
    """"2026-11-01T22:00:00Z"}}"""

/**
 * A volume of a series read to the end late on an October evening in UTC, already November in Paris; marked read
 * from Komga's web interface, so its page is the last one.
 */
const val ReadVolumeJson = """{"id":"0BK4D","seriesId":"0SR1A","seriesTitle":"Tidewater","libraryId":"0LB1",""" +
    """"name":"Tidewater 01","number":1,"created":"2026-09-01T09:00:00Z","lastModified":"2026-10-31T23:30:00Z",""" +
    """"oneshot":false,"deleted":false,""" +
    """"media":{"status":"READY","mediaType":"application/zip","mediaProfile":"DIVINA","pagesCount":40},""" +
    """"metadata":{"title":"First Tide","number":"1","numberSort":1.0,"authors":[{"name":"Writer One",""" +
    """"role":"writer"},{"name":"Artist Two","role":"penciller"}]},""" +
    """"readProgress":{"page":40,"completed":true,"readDate":"2026-10-31T23:30:00Z",""" +
    """"created":"2026-10-20T18:00:00Z","lastModified":"2026-10-31T23:30:00Z"}}"""

/** A book in the trash: its file is gone from the library. */
const val DeletedBookJson = """{"id":"0BK9Z","seriesId":"0SR1A","seriesTitle":"Tidewater","name":"Tidewater 00",""" +
    """"created":"2026-07-01T09:00:00Z","lastModified":"2026-11-15T09:00:00Z","oneshot":false,"deleted":true,""" +
    """"media":{"pagesCount":10},"metadata":{"title":"Lost Issue","number":"0"}}"""

/** The series of [ReadingBookJson], with its genres, publisher and the authors of its books. */
const val SeriesJson = """{"id":"0SR1A","libraryId":"0LB1","name":"Tidewater","url":"",""" +
    """"created":"2026-09-01T10:00:00Z","lastModified":"2026-11-20T08:15:00Z",""" +
    """"fileLastModified":"2026-09-30T18:00:00Z","booksCount":5,"booksReadCount":2,"booksUnreadCount":2,""" +
    """"booksInProgressCount":1,"metadata":{"status":"ONGOING","title":"Tidewater Tales","titleSort":"Tidewater",""" +
    """"summary":"Harbour stories.","readingDirection":"LEFT_TO_RIGHT","publisher":"North Press",""" +
    """"language":"en","genres":["Adventure","Drama"],"tags":[],"totalBookCount":null},""" +
    """"booksMetadata":{"authors":[{"name":"Writer One","role":"writer"},{"name":"Artist Two",""" +
    """"role":"penciller"},{"name":"Writer One","role":"colorist"}],"tags":[],"releaseDate":"2017-02-01",""" +
    """"summary":""},"deleted":false,"oneshot":false}"""

/** A series whose metadata has no title of its own, and no publisher nor language. */
const val UntitledSeriesJson = """{"id":"0SR2B","name":"Lantern","created":"2026-08-01T09:00:00Z",""" +
    """"lastModified":"2026-08-03T09:00:00Z","booksCount":1,"booksReadCount":1,""" +
    """"metadata":{"title":"","titleSort":"Lantern","summary":"","genres":[],"publisher":"","language":""},""" +
    """"booksMetadata":{"authors":[]},"deleted":false,"oneshot":true}"""

/** A series in the trash. */
const val DeletedSeriesJson = """{"id":"0SR9Z","name":"Gone","booksCount":1,"booksReadCount":0,""" +
    """"metadata":{"title":"Gone"},"booksMetadata":{"authors":[]},"deleted":true,"oneshot":false}"""

/** A read list of two books, in its order. */
const val ReadListJson = """{"id":"0RL1","name":"Harbour Saga","summary":"","ordered":true,""" +
    """"bookIds":["0BK2B","0BK1A"],"createdDate":"2026-11-01T10:00:00Z",""" +
    """"lastModifiedDate":"2026-11-10T10:00:00Z","filtered":false}"""

/** A Spring page of [items] (JSON objects), the [total] number of items there are in all. */
fun pageJson(vararg items: String, total: Int = items.size, number: Int = 0): String =
    """{"content":[${items.joinToString(",")}],"pageable":{"pageNumber":$number},"totalElements":$total,""" +
        """"totalPages":1,"last":true,"number":$number,"size":20,"first":${number == 0},""" +
        """"numberOfElements":${items.size},"empty":${items.isEmpty()},"sort":{"sorted":true}}"""

/** [ReadingBookJson] with its id changed to [id]: as many books as a page needs. */
fun bookJsonWithId(id: String): String = ReadingBookJson.replace(""""id":"0BK1A"""", """"id":"$id"""")
