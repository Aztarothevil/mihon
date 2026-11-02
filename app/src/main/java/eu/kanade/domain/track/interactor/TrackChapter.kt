package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject

@Inject
class TrackChapter {

    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        // Trackers disabled: no reading progress is sent to any server
        return
    }
}
