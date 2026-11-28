package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.source.audiobookshelf.library.AbsReadings
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemsPageDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMeDto
import app.tfdn.lirelle.source.audiobookshelf.network.AbsJson

/** The items of the recorded list [name] (its `results`), as recorded on [on]. */
internal fun recordedItems(name: String, on: RecordedOn = RecordedOn.Latest): List<AbsItemDto> =
    AbsJson.decodeFromString<AbsItemsPageDto>(fixtureBody(name, on)).results

/** The item titled [title] of the recorded list [name], as recorded on [on]. */
internal fun recordedItem(
    title: String,
    name: String = "items-livres-all",
    on: RecordedOn = RecordedOn.Latest,
): AbsItemDto = recordedItems(name, on).first { it.media.metadata.title == title }

/** The recorded answer [name] of one item, `GET api/items/:id?expanded=1`, as recorded on [on]. */
internal fun expandedItem(name: String, on: RecordedOn = RecordedOn.Latest): AbsItemDto =
    AbsJson.decodeFromString(fixtureBody(name, on))

/** The reader's progress, as `GET api/me` gave it on [on]. */
internal fun recordedReadings(on: RecordedOn = RecordedOn.Latest): AbsReadings =
    AbsReadings.of(AbsJson.decodeFromString<AbsMeDto>(fixtureBody("me", on)).mediaProgress)
