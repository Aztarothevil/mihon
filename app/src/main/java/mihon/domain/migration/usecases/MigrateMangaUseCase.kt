package mihon.domain.migration.usecases

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.injectLazy
import kotlin.time.Clock

@Inject
class MigrateMangaUseCase(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val sourcePreferences: SourcePreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val upsertTrack: UpsertTrack,
    private val coverCache: CoverCache,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {
    private val context: Context by injectLazy()
    private val storageManager: StorageManager by injectLazy()
    private val localSourceFileSystem: LocalSourceFileSystem by injectLazy()
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    suspend operator fun invoke(current: Manga, target: Manga, replace: Boolean) {
        val targetSource = sourceManager.get(target.source) ?: return
        val currentSource = sourceManager.get(current.source)
        val flags = sourcePreferences.migrationFlags.get()
        val notificationId = (target.id xor 0x502).toInt()

        try {
            context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                setContentTitle("Migrando manga: ${current.title}")
                setContentText("Iniciando migración a ${targetSource.name}...")
                setSmallIcon(R.drawable.ic_mihon)
                setProgress(0, 0, true)
                setOngoing(true)
                setOnlyAlertOnce(true)
            }

            updateMangaFromRemote(target, fetchChapters = true).getOrThrow()

            // Update chapters read, bookmark and dateFetch
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)
                val mangaChapters = getChaptersByMangaId.await(target.id)

                val maxChapterRead = prevMangaChapters
                    .filter { it.read }
                    .maxOfOrNull { it.chapterNumber }

                val chapterUpdates = mangaChapters
                    .filter { it.isRecognizedNumber }
                    .map { mangaChapter ->
                        val prevChapter = prevMangaChapters
                            .find { it.isRecognizedNumber && it.chapterNumber == mangaChapter.chapterNumber }

                        ChapterUpdate(mangaChapter.id) {
                            if (prevChapter != null) {
                                dateFetch = prevChapter.dateFetch
                                bookmark = prevChapter.bookmark
                            }

                            if (maxChapterRead != null && mangaChapter.chapterNumber <= maxChapterRead) {
                                read = true
                            }
                        }
                    }
                updateChapter.awaitAll(chapterUpdates)
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setMangaCategories.await(target.id, categoryIds)
            }

            // Update track
            getTracks.await(current.id).mapNotNull { track ->
                val updatedTrack = track.copy(mangaId = target.id)

                val service = enhancedServices
                    .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                if (service != null) {
                    service.migrateTrack(updatedTrack, target, targetSource)
                } else {
                    updatedTrack
                }
            }
                .takeIf { it.isNotEmpty() }
                ?.let { upsertTrack.awaitAll(it) }

            // Delete downloaded OR move/rename downloaded chapters to target source and register unregistered chapters
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                // Not awaited, so a slow storage delete doesn't hold up the migration
                scope.launch { downloadManager.deleteManga(current, currentSource) }
            } else {
                migrateDownloadedChapters(current, target, currentSource, targetSource)
            }

            // Update custom cover (recheck if custom cover exists)
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.getCustomCoverFile(current.id).inputStream().use {
                    coverCache.setCustomCoverToCache(target, it)
                }
            }

            val currentMangaUpdate = MangaUpdate(current.id) {
                favoriteAt = null
            }
                .takeIf { replace }
            val targetMangaUpdate = MangaUpdate(target.id) {
                favoriteAt = current.favoriteAt?.takeIf { replace } ?: Clock.System.now()
                chapterFlags = current.chapterFlags
                viewerFlags = current.viewerFlags
                if (MigrationFlag.NOTES in flags) notes = current.notes
            }

            updateManga.awaitAll(listOfNotNull(currentMangaUpdate, targetMangaUpdate))
            if (replace) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)
                chapterRepository.updateFromRemote(removedIds = prevMangaChapters.map { it.id }, added = emptyList(), updated = emptyList())
            }

            context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                setContentTitle("Migración completada: ${target.title}")
                setContentText("Se migró exitosamente a ${targetSource.name}")
                setSmallIcon(R.drawable.ic_mihon)
                setProgress(0, 0, false)
                setOngoing(false)
                setAutoCancel(true)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
        }
    }

    private suspend fun migrateDownloadedChapters(
        current: Manga,
        target: Manga,
        currentSource: Source?,
        targetSource: Source,
    ) {
        logcat(LogPriority.INFO) { "MigrateMangaUseCase: Starting downloaded chapter migration from '${current.title}' to '${target.title}' (${targetSource.name})" }

        val downloadsDir = storageManager.getDownloadsDirectory()
        val mangaDirName = downloadProvider.getMangaDirName(current.title)
        val validMangaName = DiskUtil.buildValidFilename(current.title)

        var downloadMangaDir: UniFile? = null

        if (currentSource != null) {
            downloadMangaDir = downloadProvider.findMangaDir(current.title, currentSource)
        }

        if (downloadMangaDir == null && downloadsDir != null && downloadsDir.exists()) {
            downloadsDir.listFiles().orEmpty().forEach { srcFolder ->
                if (downloadMangaDir == null && srcFolder.isDirectory) {
                    val found = srcFolder.findFile(current.title)
                        ?: srcFolder.findFile(validMangaName)
                        ?: srcFolder.findFile(mangaDirName)
                        ?: srcFolder.listFiles().orEmpty().firstOrNull {
                            it.isDirectory && (
                                it.name.equals(current.title, ignoreCase = true) ||
                                    it.name.equals(validMangaName, ignoreCase = true) ||
                                    it.name.equals(mangaDirName, ignoreCase = true)
                                )
                        }
                    if (found != null) {
                        downloadMangaDir = found
                    }
                }
            }
        }

        if (downloadMangaDir == null) {
            downloadMangaDir = storageManager.getFinishedSourceDirectory()?.findFile(current.title)
                ?: storageManager.getFinishedSourceDirectory()?.findFile(validMangaName)
                ?: storageManager.getFinishedSourceDirectory()?.findFile(mangaDirName)
                ?: storageManager.getLocalSourceDirectory()?.findFile(current.title)
                ?: storageManager.getLocalSourceDirectory()?.findFile(validMangaName)
                ?: storageManager.getLocalSourceDirectory()?.findFile(current.url)
                ?: localSourceFileSystem.getMangaDirectory(current.url)
        }

        if (downloadMangaDir == null || !downloadMangaDir.exists() || !downloadMangaDir.isDirectory) {
            logcat(LogPriority.WARN) { "MigrateMangaUseCase: Source download directory not found for '${current.title}'" }
            return
        }

        val targetMangaDir = downloadProvider.getMangaDir(target.title, targetSource).getOrNull()
            ?: downloadProvider.findSourceDir(targetSource)?.findFile(target.title)
            ?: downloadProvider.findSourceDir(targetSource)?.findFile(DiskUtil.buildValidFilename(target.title))

        if (targetMangaDir == null) {
            logcat(LogPriority.WARN) { "MigrateMangaUseCase: Target download directory could not be created/found for '${target.title}' in ${targetSource.name}" }
            return
        }

        val sourcePathStr = downloadMangaDir.displayablePath
        val targetPathStr = targetMangaDir.displayablePath

        logcat(LogPriority.INFO) { "[Migrate Directory] Source path: '$sourcePathStr' ---> Target path: '$targetPathStr'" }

        if (downloadMangaDir.uri == targetMangaDir.uri) {
            logcat(LogPriority.INFO) { "MigrateMangaUseCase: Source and target directory URIs are identical. Skipping file move." }
            return
        }

        val prevMangaChapters = getChaptersByMangaId.await(current.id)
        val targetMangaChapters = getChaptersByMangaId.await(target.id)

        val downloadedFiles = downloadMangaDir.listFiles().orEmpty()
            .filter { !it.name.orEmpty().startsWith('.') && it.name != "cover.jpg" }

        val totalFiles = downloadedFiles.size
        logcat(LogPriority.INFO) { "[Migrate Chapter Stage 2] Found $totalFiles physical files in '$sourcePathStr' to move to '$targetPathStr'" }

        if (downloadedFiles.isEmpty()) {
            logcat(LogPriority.WARN) { "[Migrate Chapter Stage 2] 0 physical files found in '$sourcePathStr'. Nothing to move." }
            return
        }

        val usedNames = mutableSetOf<String>()
        val notificationId = (target.id xor 0x502).toInt()

        downloadedFiles.forEachIndexed { index, file ->
            val currentProgress = index + 1
            context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                setContentTitle("Migrando descargas: ${current.title}")
                setContentText("$currentProgress / $totalFiles capítulos")
                setSmallIcon(R.drawable.ic_mihon)
                setProgress(totalFiles, currentProgress, false)
                setOngoing(true)
                setOnlyAlertOnce(true)
            }
            val fileNameNoExt = file.nameWithoutExtension.orEmpty()
            val fileNameRaw = file.name.orEmpty()

            var matchedChapter = targetMangaChapters.find { chapter ->
                downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                    .any { it.equals(fileNameNoExt, ignoreCase = true) || it.equals(fileNameRaw, ignoreCase = true) }
            } ?: prevMangaChapters.find { chapter ->
                downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                    .any { it.equals(fileNameNoExt, ignoreCase = true) || it.equals(fileNameRaw, ignoreCase = true) }
            }

            if (matchedChapter == null) {
                matchedChapter = targetMangaChapters.find { chapter ->
                    val validName = DiskUtil.buildValidFilename(chapter.name)
                    fileNameNoExt.equals(chapter.name, ignoreCase = true) || fileNameNoExt.equals(validName, ignoreCase = true)
                } ?: prevMangaChapters.find { chapter ->
                    val validName = DiskUtil.buildValidFilename(chapter.name)
                    fileNameNoExt.equals(chapter.name, ignoreCase = true) || fileNameNoExt.equals(validName, ignoreCase = true)
                }
            }

            if (matchedChapter == null) {
                matchedChapter = targetMangaChapters.find { chapter ->
                    val validName = DiskUtil.buildValidFilename(chapter.name)
                    validName.isNotBlank() && (
                        fileNameNoExt.endsWith("_$validName", ignoreCase = true) ||
                            fileNameNoExt.endsWith(" $validName", ignoreCase = true) ||
                            fileNameNoExt.endsWith("-$validName", ignoreCase = true) ||
                            fileNameNoExt.endsWith(validName, ignoreCase = true)
                        )
                } ?: prevMangaChapters.find { chapter ->
                    val validName = DiskUtil.buildValidFilename(chapter.name)
                    validName.isNotBlank() && (
                        fileNameNoExt.endsWith("_$validName", ignoreCase = true) ||
                            fileNameNoExt.endsWith(" $validName", ignoreCase = true) ||
                            fileNameNoExt.endsWith("-$validName", ignoreCase = true) ||
                            fileNameNoExt.endsWith(validName, ignoreCase = true)
                        )
                }
            }

            if (matchedChapter == null && fileNameNoExt.contains('_')) {
                val withoutHash = if (fileNameNoExt.length > 7 && fileNameNoExt[fileNameNoExt.length - 7] == '_') {
                    fileNameNoExt.substring(0, fileNameNoExt.length - 7)
                } else {
                    fileNameNoExt
                }
                matchedChapter = targetMangaChapters.find { chapter ->
                    val validName = DiskUtil.buildValidFilename(chapter.name)
                    validName.isNotBlank() && (
                        withoutHash.endsWith("_$validName", ignoreCase = true) ||
                            withoutHash.endsWith(" $validName", ignoreCase = true) ||
                            withoutHash.endsWith("-$validName", ignoreCase = true) ||
                            withoutHash.endsWith(validName, ignoreCase = true)
                        )
                } ?: prevMangaChapters.find { chapter ->
                    val validName = DiskUtil.buildValidFilename(chapter.name)
                    validName.isNotBlank() && (
                        withoutHash.endsWith("_$validName", ignoreCase = true) ||
                            withoutHash.endsWith(" $validName", ignoreCase = true) ||
                            withoutHash.endsWith("-$validName", ignoreCase = true) ||
                            withoutHash.endsWith(validName, ignoreCase = true)
                        )
                }
            }

            if (matchedChapter == null) {
                val fileChapterNumber = ChapterRecognition.parseChapterNumber(target.title, fileNameNoExt, -1.0)
                if (fileChapterNumber >= 0) {
                    matchedChapter = targetMangaChapters.find { it.chapterNumber > 0 && it.chapterNumber == fileChapterNumber }
                        ?: prevMangaChapters.find { it.chapterNumber > 0 && it.chapterNumber == fileChapterNumber }
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
            var version = 2
            while (usedNames.contains(candidateName)) {
                candidateName = "$baseName.v$version$ext"
                version++
            }
            usedNames.add(candidateName)

            moveUniFile(file, targetMangaDir, candidateName)

            if (matchedChapter != null && matchedChapter in targetMangaChapters) {
                val newUrl = if (matchedChapter.url.contains("#migrated_")) {
                    matchedChapter.url
                } else {
                    "${matchedChapter.url}#migrated_${DiskUtil.hashKeyForDisk(candidateName)}"
                }
                runCatching { chapterRepository.updateUrl(matchedChapter.id, newUrl) }
            }
        }

        runCatching { downloadMangaDir.delete() }

        val updatedTargetChapters = getChaptersByMangaId.await(target.id)
        val targetDownloadedFiles = targetMangaDir.listFiles().orEmpty()
            .filter { !it.name.orEmpty().startsWith('.') && it.name != "cover.jpg" }

        val unregisteredChapters = mutableListOf<Chapter>()
        targetDownloadedFiles.forEach { file ->
            val fileNameNoExt = file.nameWithoutExtension.orEmpty()
            val isRegistered = updatedTargetChapters.any { chapter ->
                downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                    .any { it.equals(fileNameNoExt, ignoreCase = true) } ||
                    DiskUtil.buildValidFilename(chapter.name).equals(fileNameNoExt, ignoreCase = true)
            }

            if (!isRegistered) {
                val prevMatch = prevMangaChapters.find { chapter ->
                    downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                        .any { it.equals(fileNameNoExt, ignoreCase = true) } ||
                        DiskUtil.buildValidFilename(chapter.name).equals(fileNameNoExt, ignoreCase = true) ||
                        (chapter.chapterNumber > 0 && chapter.chapterNumber == ChapterRecognition.parseChapterNumber(target.title, fileNameNoExt, -1.0))
                }

                val chNum = prevMatch?.chapterNumber ?: ChapterRecognition.parseChapterNumber(target.title, fileNameNoExt, -1.0)
                val newUnregisteredChapter = Chapter.create().copy(
                    mangaId = target.id,
                    url = "${target.url}#migrated_${DiskUtil.hashKeyForDisk(fileNameNoExt)}",
                    name = prevMatch?.name ?: fileNameNoExt,
                    chapterNumber = if (chNum >= 0) chNum else -1.0,
                    scanlator = prevMatch?.scanlator,
                    read = prevMatch?.read ?: false,
                    bookmark = prevMatch?.bookmark ?: false,
                    lastPageRead = prevMatch?.lastPageRead ?: 0,
                    dateFetch = prevMatch?.dateFetch ?: Clock.System.now(),
                    dateUpload = prevMatch?.dateUpload,
                )
                unregisteredChapters.add(newUnregisteredChapter)
            }
        }

        if (unregisteredChapters.isNotEmpty()) {
            chapterRepository.updateFromRemote(emptyList(), unregisteredChapters, emptyList())
        }

        downloadManager.setMangaChapterDirs(current, emptySet())
        val targetFileNamesNoExt = targetDownloadedFiles.map { if (it.isDirectory) it.name.orEmpty() else it.nameWithoutExtension.orEmpty() }.toSet()
        downloadManager.setMangaChapterDirs(target, targetFileNamesNoExt)
    }

    private fun moveUniFile(sourceFile: UniFile, targetDir: UniFile, newName: String) {
        try {
            val sourcePath = sourceFile.displayablePath
            val targetParentPath = targetDir.displayablePath
            val targetPath = "$targetParentPath/$newName"

            logcat(LogPriority.INFO) { "[Migrate File Copy] '$sourcePath' ---> '$targetPath'" }

            if (sourceFile.name == newName && sourceFile.parentFile?.uri == targetDir.uri) {
                logcat(LogPriority.INFO) { "[Migrate File Copy] Source and target are identical. Skipping." }
                return
            }

            if (sourceFile.parentFile?.uri == targetDir.uri && sourceFile.renameTo(newName)) {
                logcat(LogPriority.INFO) { "[Migrate File Copy] Renamed file in-place to '$newName'" }
                return
            }

            if (sourceFile.isFile) {
                val destination = targetDir.findFile(newName) ?: targetDir.createFile(newName)
                if (destination == null) {
                    logcat(LogPriority.ERROR) { "[Migrate File Copy Error] Failed to create destination file '$newName' in '$targetParentPath'" }
                    return
                }

                val bytesCopied = sourceFile.openInputStream().use { input ->
                    destination.openOutputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                logcat(LogPriority.INFO) { "[Migrate File Copy Success] Copied $bytesCopied bytes for '$newName' from '$sourcePath' ---> '$targetPath'" }
                val deleted = runCatching { sourceFile.delete() }.getOrDefault(false)
                logcat(LogPriority.INFO) { "[Migrate File Copy] Deleted original file '$sourcePath': $deleted" }
            } else if (sourceFile.isDirectory) {
                val destination = targetDir.findFile(newName)?.takeIf { it.isDirectory } ?: targetDir.createDirectory(newName)
                if (destination == null) {
                    logcat(LogPriority.ERROR) { "[Migrate File Copy Error] Failed to create destination directory '$newName' in '$targetParentPath'" }
                    return
                }

                sourceFile.listFiles().orEmpty().forEach { child ->
                    moveUniFile(child, destination, child.name.orEmpty())
                }
                val deleted = runCatching { sourceFile.delete() }.getOrDefault(false)
                logcat(LogPriority.INFO) { "[Migrate File Copy] Deleted original directory '$sourcePath': $deleted" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[Migrate File Copy Error] Failed to move file '${sourceFile.name}' to '$newName' in '${targetDir.name}'" }
        }
    }
}
