package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource

@Inject
@SingleIn(AppScope::class)
class SyncFinishedDirectory(
    private val storageManager: StorageManager,
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val setMangaCategories: SetMangaCategories,
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val updateManga: UpdateManga,
    private val syncChaptersWithSource: SyncChaptersWithSource,
) {

    suspend fun await() = withContext(Dispatchers.IO) {
        try {
            // 1. Get/Create "Finished" category
            var categories = getCategories.await()
            var finishedCategory = categories.firstOrNull { it.name.equals("Finished", ignoreCase = true) }
            if (finishedCategory == null) {
                createCategoryWithName.await("Finished")
                categories = getCategories.await()
                finishedCategory = categories.firstOrNull { it.name.equals("Finished", ignoreCase = true) }
            }
            val finishedCategoryId = finishedCategory?.id ?: return@withContext

            // 2. Scan finished directory
            val finishedDir = storageManager.getFinishedSourceDirectory() ?: return@withContext
            val mangaDirs = finishedDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }

            if (mangaDirs.isEmpty()) return@withContext

            val localSource = sourceManager.get(LocalSource.ID) as? LocalSource ?: return@withContext

            for (mangaDir in mangaDirs) {
                val title = mangaDir.name ?: continue
                var localManga = mangaRepository.getMangaByUrlAndSourceId(title, LocalSource.ID)
                if (localManga == null) {
                    val newLocalManga = Manga.create().copy(
                        url = title,
                        title = title,
                        source = LocalSource.ID,
                        favoriteAt = Clock.System.now(),
                    )
                    val inserted = mangaRepository.insertNetworkManga(listOf(newLocalManga))
                    localManga = inserted.firstOrNull() ?: continue
                    updateManga.awaitUpdateFavorite(localManga.id, true)
                    localManga = localManga.copy(favoriteAt = Clock.System.now())
                } else if (!localManga.favorite) {
                    updateManga.awaitUpdateFavorite(localManga.id, true)
                    localManga = localManga.copy(favoriteAt = Clock.System.now())
                }

                // Assign to "Finished" category
                setMangaCategories.await(localManga.id, listOf(finishedCategoryId))

                // Sync chapters for local manga
                val rawChapters = localSource.getChapterList(localManga.toSManga())
                syncChaptersWithSource.await(rawChapters, localManga, localSource)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to sync finished directory" }
        }
    }
}
