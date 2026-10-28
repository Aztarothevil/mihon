package eu.kanade.tachiyomi.util

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.isLocal
import java.io.InputStream

fun Manga.removeCovers(coverCache: CoverCache) {
    if (isLocal()) return
    coverCache.deleteFromCache(this, true)
}

suspend fun Manga.editCover(
    coverManager: LocalCoverManager,
    stream: InputStream,
    updateManga: UpdateManga,
    coverCache: CoverCache,
    downloadProvider: DownloadProvider? = null,
    sourceManager: SourceManager? = null,
) {
    if (isLocal()) {
        coverManager.update(toSManga(), stream)
        updateManga.awaitUpdateCoverLastModified(id)
    } else {
        val bytes = stream.readBytes()
        if (favorite) {
            coverCache.setCustomCoverToCache(this, bytes.inputStream())
            updateManga.awaitUpdateCoverLastModified(id)
        }
        if (downloadProvider != null && sourceManager != null) {
            val source = sourceManager.getOrStub(source)
            downloadProvider.saveMangaCover(title, source, bytes.inputStream())
            updateManga.awaitUpdateCoverLastModified(id)
        }
    }
}
