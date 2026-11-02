package eu.kanade.domain.track.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.Tracker
import tachiyomi.domain.track.model.Track

@Inject
class SyncChapterProgressWithTrack {

    suspend fun await(
        mangaId: Long,
        remoteTrack: Track,
        tracker: Tracker,
    ) {
        // Trackers disabled: no reading progress is synced with any server
        return
    }
}
