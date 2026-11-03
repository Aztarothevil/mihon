package eu.kanade.domain.manga.interactor

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
class MigrateToFinished(
    private val context: Context,
    private val storageManager: StorageManager,
    private val downloadProvider: DownloadProvider,
    private val coverCache: CoverCache,
    private val sourceManager: SourceManager,
    private val mangaRepository: MangaRepository,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val createCategoryWithName: CreateCategoryWithName,
    private val setMangaCategories: SetMangaCategories,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val syncChaptersWithSource: SyncChaptersWithSource,
) {

    suspend fun await(manga: Manga): Result = withContext(Dispatchers.IO) {
        if (manga.source == LocalSource.ID) {
            val categories = getCategories.await(manga.id)
            if (categories.any { it.name.equals("Finished", ignoreCase = true) }) {
                return@withContext Result.AlreadyLocal
            }
        }

        val notificationId = (manga.id xor 0x502).toInt()

        try {
            context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                setContentTitle("Migrando a Finished: ${manga.title}")
                setContentText("Iniciando migración...")
                setSmallIcon(R.drawable.ic_mihon)
                setProgress(0, 0, true)
                setOngoing(true)
                setOnlyAlertOnce(true)
            }

            val source = sourceManager.getOrStub(manga.source)
            val chapters = getChaptersByMangaId.await(manga.id)

            // 1. Get/Create "Finished" category
            var categories = getCategories.await()
            var finishedCategory = categories.firstOrNull { it.name.equals("Finished", ignoreCase = true) }
            if (finishedCategory == null) {
                createCategoryWithName.await("Finished")
                categories = getCategories.await()
                finishedCategory = categories.firstOrNull { it.name.equals("Finished", ignoreCase = true) }
            }
            val finishedCategoryId = finishedCategory?.id ?: return@withContext Result.Error(Exception("Finished category missing"))

            // 2. Get finished source directory
            val finishedSourceDir = storageManager.getFinishedSourceDirectory()
                ?: return@withContext Result.Error(Exception("Finished source directory unavailable"))

            val mangaDirName = DiskUtil.buildValidFilename(manga.title)
            val targetMangaDir = finishedSourceDir.findFile(mangaDirName)?.takeIf { it.isDirectory }
                ?: finishedSourceDir.createDirectory(mangaDirName)
                ?: return@withContext Result.Error(Exception("Could not create finished manga directory"))

            // 3. Move downloaded chapters & rename them preserving presentation name
            val chapterToOldChapterMap = mutableMapOf<String, Chapter>()
            val downloadMangaDir = downloadProvider.findMangaDir(manga.title, source)
            if (downloadMangaDir != null && downloadMangaDir.exists()) {
                val downloadedFiles = downloadMangaDir.listFiles().orEmpty()
                    .filter { !it.name.orEmpty().startsWith('.') && it.name != "cover.jpg" }

                val totalFiles = downloadedFiles.size
                val usedNames = mutableSetOf<String>()

                downloadedFiles.forEachIndexed { index, file ->
                    val currentProgress = index + 1
                    context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                        setContentTitle("Migrando a Finished: ${manga.title}")
                        setContentText("$currentProgress / $totalFiles capítulos")
                        setSmallIcon(R.drawable.ic_mihon)
                        setProgress(totalFiles, currentProgress, false)
                        setOngoing(true)
                        setOnlyAlertOnce(true)
                    }

                    val fileNameNoExt = file.nameWithoutExtension.orEmpty()
                    val fileNameRaw = file.name.orEmpty()

                    // 1. Try exact match using DownloadProvider valid dir names
                    var matchedChapter = chapters.find { chapter ->
                        downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                            .any { it.equals(fileNameNoExt, ignoreCase = true) || it.equals(fileNameRaw, ignoreCase = true) }
                    }

                    // 2. Try matching chapter name or sanitized chapter name
                    if (matchedChapter == null) {
                        matchedChapter = chapters.find { chapter ->
                            val validName = DiskUtil.buildValidFilename(chapter.name)
                            fileNameNoExt.equals(chapter.name, ignoreCase = true) ||
                                fileNameNoExt.equals(validName, ignoreCase = true)
                        }
                    }

                    // 3. Try matching if fileNameNoExt ends with chapter name or sanitized chapter name
                    if (matchedChapter == null) {
                        matchedChapter = chapters.find { chapter ->
                            val validName = DiskUtil.buildValidFilename(chapter.name)
                            validName.isNotBlank() && (
                                fileNameNoExt.endsWith("_$validName", ignoreCase = true) ||
                                    fileNameNoExt.endsWith(" $validName", ignoreCase = true) ||
                                    fileNameNoExt.endsWith("-$validName", ignoreCase = true) ||
                                    fileNameNoExt.endsWith(validName, ignoreCase = true)
                                )
                        }
                    }

                    // 4. Try matching after stripping hash suffix (_xxxxxx) if present
                    if (matchedChapter == null && fileNameNoExt.contains('_')) {
                        val withoutHash = if (fileNameNoExt.length > 7 && fileNameNoExt[fileNameNoExt.length - 7] == '_') {
                            fileNameNoExt.substring(0, fileNameNoExt.length - 7)
                        } else {
                            fileNameNoExt
                        }
                        matchedChapter = chapters.find { chapter ->
                            val validName = DiskUtil.buildValidFilename(chapter.name)
                            validName.isNotBlank() && (
                                withoutHash.endsWith("_$validName", ignoreCase = true) ||
                                    withoutHash.endsWith(" $validName", ignoreCase = true) ||
                                    withoutHash.endsWith("-$validName", ignoreCase = true) ||
                                    withoutHash.endsWith(validName, ignoreCase = true)
                                )
                        }
                    }

                    // 5. Try matching by chapter number using ChapterRecognition
                    if (matchedChapter == null) {
                        val fileChapterNumber = ChapterRecognition
                            .parseChapterNumber(manga.title, fileNameNoExt, -1.0)
                        if (fileChapterNumber >= 0) {
                            matchedChapter = chapters.find { it.chapterNumber > 0 && it.chapterNumber == fileChapterNumber }
                        }
                    }

                    val isArchive = file.name.orEmpty().endsWith(".cbz", ignoreCase = true) || file.name.orEmpty().endsWith(".zip", ignoreCase = true)
                    val ext = if (file.isFile && isArchive) "." + file.extension else ""

                    val chapterNumberStr = when {
                        matchedChapter != null && matchedChapter.chapterNumber > 0 -> {
                            val num = matchedChapter.chapterNumber
                            if (num % 1 == 0.0) num.toInt().toString() else num.toString()
                        }
                        else -> (index + 1).toString()
                    }

                    val baseName = when {
                        matchedChapter != null && matchedChapter.name.isNotBlank() -> {
                            DiskUtil.buildValidFilename(matchedChapter.name).ifBlank { fileNameNoExt }
                        }
                        fileNameNoExt.isNotBlank() -> {
                            val cleanName = if (fileNameNoExt.contains('_')) {
                                fileNameNoExt.substringAfter('_').ifBlank { fileNameNoExt }
                            } else {
                                fileNameNoExt
                            }
                            DiskUtil.buildValidFilename(cleanName).ifBlank { "Chapter_$chapterNumberStr" }
                        }
                        else -> "Chapter_$chapterNumberStr"
                    }

                    var candidateName = "$baseName$ext"
                    var counter = 1
                    while (usedNames.contains(candidateName) || targetMangaDir.findFile(candidateName) != null) {
                        candidateName = "${baseName}_$counter$ext"
                        counter++
                    }
                    usedNames.add(candidateName)
                    val newChapterDirName = candidateName

                    if (matchedChapter != null) {
                        chapterToOldChapterMap[newChapterDirName] = matchedChapter
                        chapterToOldChapterMap[candidateName.removeSuffix(ext)] = matchedChapter
                        chapterToOldChapterMap[matchedChapter.name] = matchedChapter
                        chapterToOldChapterMap[DiskUtil.buildValidFilename(matchedChapter.name)] = matchedChapter
                    }

                    moveUniFile(file, targetMangaDir, newChapterDirName)
                }

                // Clean up original download folder if empty or only .nomedia left
                try {
                    downloadMangaDir.delete()
                } catch (_: Exception) {}
            }

            // 4. Save/Copy Cover to finished manga directory as cover.jpg
            val coverFile = coverCache.getCoverFile(manga.thumbnailUrl)
            if (coverFile?.exists() == true) {
                try {
                    coverFile.inputStream().use { input ->
                        val coverTarget = targetMangaDir.findFile("cover.jpg") ?: targetMangaDir.createFile("cover.jpg")
                        coverTarget?.openOutputStream()?.use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Failed to copy cover during migration to finished" }
                }
            }

            // 5. Insert/Update Manga into DB as Local Source Manga
            val localSource = sourceManager.get(LocalSource.ID) as? LocalSource
                ?: return@withContext Result.Error(Exception("Local source unavailable"))

            var localManga = mangaRepository.getMangaByUrlAndSourceId(mangaDirName, LocalSource.ID)
            if (localManga == null && manga.title != mangaDirName) {
                val legacyManga = mangaRepository.getMangaByUrlAndSourceId(manga.title, LocalSource.ID)
                if (legacyManga != null) {
                    setMangaCategories.await(legacyManga.id, emptyList())
                    updateManga.awaitUpdateFavorite(legacyManga.id, false)
                }
            }

            if (localManga == null) {
                val newLocalManga = Manga.create().copy(
                    url = mangaDirName,
                    title = manga.title,
                    source = LocalSource.ID,
                    artist = manga.artist,
                    author = manga.author,
                    description = manga.description,
                    genre = manga.genre,
                    status = manga.status,
                    favoriteAt = Clock.System.now(),
                )
                val inserted = mangaRepository.insertNetworkManga(listOf(newLocalManga))
                localManga = inserted.firstOrNull() ?: return@withContext Result.Error(Exception("Failed to insert local manga"))
                updateManga.awaitUpdateFavorite(localManga.id, true)
                localManga = localManga.copy(favoriteAt = Clock.System.now())
            } else if (!localManga.favorite) {
                updateManga.awaitUpdateFavorite(localManga.id, true)
                localManga = localManga.copy(favoriteAt = Clock.System.now())
            }

            // Copy manga flags and notes
            updateManga.await(
                MangaUpdate(localManga.id) {
                    chapterFlags = manga.chapterFlags
                    viewerFlags = manga.viewerFlags
                    if (manga.notes.isNotBlank()) notes = manga.notes
                },
            )

            // 6. Assign to "Finished" Category
            setMangaCategories.await(localManga.id, listOf(finishedCategoryId))

            // 7. Sync chapters for local manga and map read state & bookmarks
            val rawChapters = localSource.getChapterList(localManga.toSManga())
            val newChapters = syncChaptersWithSource.await(rawChapters, localManga, localSource)

            // Transfer read status, bookmark status, and last page read from old chapters to new local chapters
            val chapterUpdates = newChapters.mapNotNull { newChapter ->
                val oldChapter = chapterToOldChapterMap[newChapter.name]
                    ?: chapterToOldChapterMap[newChapter.url.substringAfterLast('/')]
                    ?: chapters.find { it.chapterNumber > 0 && it.chapterNumber == newChapter.chapterNumber }
                    ?: chapters.find { it.name.equals(newChapter.name, ignoreCase = true) }

                if (oldChapter != null) {
                    ChapterUpdate(newChapter.id) {
                        read = oldChapter.read
                        bookmark = oldChapter.bookmark
                        lastPageRead = oldChapter.lastPageRead
                        dateFetch = oldChapter.dateFetch
                    }
                } else {
                    null
                }
            }
            if (chapterUpdates.isNotEmpty()) {
                updateChapter.awaitAll(chapterUpdates)
            }

            // 8. Remove original manga from its categories and unfavorite it if it's not the same local manga entry
            if (manga.id != localManga.id) {
                setMangaCategories.await(manga.id, emptyList())
                updateManga.awaitUpdateFavorite(manga.id, false)
            }

            context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                setContentTitle("Migración a Finished completada")
                setContentText(manga.title)
                setSmallIcon(R.drawable.ic_mihon)
                setProgress(0, 0, false)
                setOngoing(false)
                setAutoCancel(true)
            }

            Result.Success(localManga)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error migrating manga ${manga.title} to finished" }
            context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                setContentTitle("Error al migrar a Finished")
                setContentText(manga.title)
                setSmallIcon(R.drawable.ic_mihon)
                setProgress(0, 0, false)
                setOngoing(false)
                setAutoCancel(true)
            }
            Result.Error(e)
        }
    }

    private fun moveUniFile(sourceFile: UniFile, targetDir: UniFile, newName: String) {
        try {
            if (sourceFile.name == newName && sourceFile.parentFile?.uri == targetDir.uri) return

            if (sourceFile.renameTo(newName)) {
                return
            }

            if (sourceFile.isFile) {
                val destination = targetDir.createFile(newName) ?: return
                sourceFile.openInputStream().use { input ->
                    destination.openOutputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                runCatching { sourceFile.delete() }
            } else if (sourceFile.isDirectory) {
                val destination = targetDir.findFile(newName)?.takeIf { it.isDirectory }
                    ?: targetDir.createDirectory(newName)
                    ?: return
                sourceFile.listFiles().orEmpty().forEach { child ->
                    moveUniFile(child, destination, child.name.orEmpty())
                }
                runCatching { sourceFile.delete() }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to move file ${sourceFile.name} to $newName" }
        }
    }

    sealed interface Result {
        data class Success(val localManga: Manga) : Result
        data object AlreadyLocal : Result
        data class Error(val error: Throwable) : Result
    }
}
