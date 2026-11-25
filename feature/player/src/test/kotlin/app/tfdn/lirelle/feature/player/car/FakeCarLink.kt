package app.tfdn.lirelle.feature.player.car

import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.SessionError

/** A car that records what it is told. */
class FakeCarLink(override val controller: Any = "car") : CarLink {
    val said = mutableListOf<SessionError>()

    /** The searches it was told the results of, each with how many. */
    val found = mutableListOf<Pair<String, Int>>()

    override fun say(error: SessionError) {
        said += error
    }

    override fun searchChanged(query: String, count: Int, params: LibraryParams?) {
        found += query to count
    }
}

/** A library that answers what the test scripted, and records what it was asked. */
class FakeCarShelves : CarShelves {
    val places = mutableMapOf<CarPlace, CarAnswer>()
    var searched: CarAnswer = CarAnswer.Nodes(emptyList())

    /** The place, the page and its size of each request. */
    val asked = mutableListOf<Triple<CarPlace, Int, Int>>()
    val searches = mutableListOf<String>()

    override suspend fun children(place: CarPlace, page: Int, pageSize: Int): CarAnswer {
        asked += Triple(place, page, pageSize)
        return places[place] ?: CarAnswer.Nodes(emptyList())
    }

    override suspend fun search(text: String): CarAnswer {
        searches += text
        return searched
    }
}
