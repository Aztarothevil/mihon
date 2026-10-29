package eu.kanade.tachiyomi.data.library

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
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
import tachiyomi.source.local.io.LocalSourceFileSystem

@Inject
@SingleIn(AppScope::class)
class LocalSourceSync(
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val setMangaCategories: SetMangaCategories,
    private val mangaRepository: MangaRepository,
    private val updateManga: UpdateManga,
    private val sourceManager: SourceManager,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val localSourceFileSystem: LocalSourceFileSystem,
    private val storageManager: StorageManager,
) {

    suspend fun await() = withContext(Dispatchers.IO) {
        try {
            // 1. Check or create "Local" category
            var categories = getCategories.await()
            var localCategory = categories.firstOrNull { it.name.equals("Local", ignoreCase = true) }
            if (localCategory == null) {
                createCategoryWithName.await("Local")
                categories = getCategories.await()
                localCategory = categories.firstOrNull { it.name.equals("Local", ignoreCase = true) }
            }
            val localCategoryId = localCategory?.id ?: return@withContext

            // 2. Get local source and local manga folders
            val localSource = sourceManager.get(LocalSource.ID) as? LocalSource ?: return@withContext
            val localDir = storageManager.getLocalSourceDirectory() ?: return@withContext
            val mangaDirs = localDir.listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }

            if (mangaDirs.isEmpty()) return@withContext

            // 3. Sync each local manga
            mangaDirs.forEach { mangaDir ->
                val title = mangaDir.name.orEmpty()
                if (title.isBlank()) return@forEach

                // Find or insert manga
                var manga = mangaRepository.getMangaByUrlAndSourceId(title, LocalSource.ID)
                if (manga == null) {
                    val newManga = Manga.create().copy(
                        url = title,
                        title = title,
                        source = LocalSource.ID,
                        favoriteAt = Clock.System.now(),
                    )
                    val inserted = mangaRepository.insertNetworkManga(listOf(newManga))
                    manga = inserted.firstOrNull() ?: return@forEach
                    updateManga.awaitUpdateFavorite(manga.id, true)
                    manga = manga.copy(favoriteAt = Clock.System.now())
                } else if (!manga.favorite) {
                    updateManga.awaitUpdateFavorite(manga.id, true)
                    manga = manga.copy(favoriteAt = Clock.System.now())
                }

                // Assign to "Local" category
                setMangaCategories.await(manga.id, listOf(localCategoryId))

                // Sync chapters
                try {
                    val rawChapters = localSource.getChapterList(manga.toSManga())
                    syncChaptersWithSource.await(rawChapters, manga, localSource)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to sync chapters for local manga $title" }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error syncing local manga on startup" }
        }
    }
}
