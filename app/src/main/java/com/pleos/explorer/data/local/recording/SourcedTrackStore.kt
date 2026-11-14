package com.pleos.explorer.data.local.recording

import com.pleos.explorer.domain.model.ExploredRoad
import com.pleos.explorer.domain.port.TrackRecordingStore

/** Simulation must not mark real roads as already explored. */
class SourcedTrackStore(private val store: RoomTrackRecordingStore, private val source: RecordingInputSource) :
    TrackRecordingStore by store {
    override suspend fun findRoads(gridKeys: Set<String>): Map<String, ExploredRoad> = store.findRoadsForSource(gridKeys, source)
}
