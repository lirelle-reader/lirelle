package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.PositionStore

/** [PositionStore] kept in memory. [positions] can be read and set directly. */
class InMemoryPositionStore(vararg initial: LocalPosition) : PositionStore {
    val positions: MutableMap<FileId, LocalPosition> = initial.associateBy { it.fileId }.toMutableMap()

    override suspend fun get(fileId: FileId): LocalPosition? = positions[fileId]

    override suspend fun put(position: LocalPosition) {
        positions[position.fileId] = position
    }
}
