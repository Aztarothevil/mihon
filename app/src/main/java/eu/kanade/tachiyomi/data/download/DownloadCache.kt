package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.core.util.writeBytes
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Cache where we dump the downloads directory from the filesystem. This class is needed because
 * directory checking is expensive and it slows down the app. The cache is invalidated by the time
 * defined in [renewInterval] as we don't have any control over the filesystem and the user can
 * delete the folders at any time without the app noticing.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadCache(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val provider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val storageManager: StorageManager,
) {

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .onStart { emit(Unit) }
        .shareIn(scope, SharingStarted.Lazily, 1)

    /**
     * The interval after which this cache should be invalidated. 1 hour shouldn't cause major
     * issues, as the cache is only used for UI feedback.
     */
    private val renewInterval = 1.hours.inWholeMilliseconds

    /**
     * The last time the cache was refreshed.
     */
    private var lastRenew = 0L
    private var renewalJob: Job? = null
    private val initJob: Job

    private val _isInitializing = MutableStateFlow(false)
    val isInitializing = _isInitializing
        .debounce(1.seconds) // Don't notify if it finishes quickly enough
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val diskCacheFile: AtomicFile
        get() = AtomicFile(File(context.cacheDir, "dl_index_cache_v3"))

    private val rootDownloadsDirMutex = Mutex()

    @Volatile
    private var rootDownloadsDir = RootDirectory(null)

    init {
        // Attempt to read cache file
        initJob = scope.launch(Dispatchers.IO) {
            rootDownloadsDirMutex.withLock {
                try {
                    if (diskCacheFile.baseFile.exists()) {
                        val diskCache = diskCacheFile.openRead().use {
                            ProtoBuf.decodeFromByteArray<RootDirectory>(it.readBytes())
                        }
                        rootDownloadsDir = diskCache
                        lastRenew = System.currentTimeMillis()
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Failed to initialize from disk cache" }
                    diskCacheFile.delete()
                }
            }
        }

        storageManager.changes
            .onEach { invalidateCache() }
            .launchIn(scope)
    }

    private fun isChapterDirMatching(
        mangaTitle: String,
        chapter: Chapter,
        chapterDirs: Collection<String>,
    ): Boolean {
        if (chapterDirs.isEmpty()) return false

        val validNames = provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
        if (validNames.any { validName -> chapterDirs.any { it.equals(validName, ignoreCase = true) } }) {
            return true
        }

        val sanitizedName = DiskUtil.buildValidFilename(chapter.name)
        if (chapterDirs.any { it.equals(chapter.name, ignoreCase = true) || it.equals(sanitizedName, ignoreCase = true) }) {
            return true
        }

        if (chapter.chapterNumber >= 0) {
            val intPart = chapter.chapterNumber.toInt()
            val decPart = chapter.chapterNumber - intPart
            val paddedInt = intPart.toString().padStart(3, '0')
            val paddedName = if (decPart > 0.0001) {
                val decStr = chapter.chapterNumber.toString().substringAfter('.')
                "Chapter $paddedInt.$decStr"
            } else {
                "Chapter $paddedInt"
            }
            if (chapterDirs.any { it.equals(paddedName, ignoreCase = true) }) {
                return true
            }
        }

        if (chapter.chapterNumber >= 0) {
            return chapterDirs.any { dirName ->
                val fileNum = ChapterRecognition.parseChapterNumber(mangaTitle, dirName, -1.0)
                fileNum >= 0 && fileNum == chapter.chapterNumber
            }
        }

        return false
    }

    /**
     * Returns the ids of the downloaded chapters among the given chapters of one manga.
     *
     * @param chapters the chapters to query, all of the same manga.
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapters.
     */
    fun getDownloadedChapterIds(chapters: List<Chapter>, mangaTitle: String, sourceId: Long): Set<Long> {
        renewCache()

        val targetMangaDirName = provider.getMangaDirName(mangaTitle)
        val validMangaName = DiskUtil.buildValidFilename(mangaTitle)

        val mangaDirs = mutableListOf<MangaDirectory>()

        val currentSourceDir = rootDownloadsDir.sourceDirs[sourceId]
        if (currentSourceDir != null) {
            val dir = currentSourceDir.mangaDirs[targetMangaDirName]
                ?: currentSourceDir.mangaDirs[validMangaName]
                ?: currentSourceDir.mangaDirs.entries.firstOrNull {
                    it.key.equals(targetMangaDirName, ignoreCase = true) ||
                        it.key.equals(validMangaName, ignoreCase = true) ||
                        it.key.equals(mangaTitle, ignoreCase = true)
                }?.value
            if (dir != null) mangaDirs.add(dir)
        }

        for ((sId, sourceDir) in rootDownloadsDir.sourceDirs) {
            if (sId == sourceId) continue
            val dir = sourceDir.mangaDirs[targetMangaDirName]
                ?: sourceDir.mangaDirs[validMangaName]
                ?: sourceDir.mangaDirs.entries.firstOrNull {
                    it.key.equals(targetMangaDirName, ignoreCase = true) ||
                        it.key.equals(validMangaName, ignoreCase = true) ||
                        it.key.equals(mangaTitle, ignoreCase = true)
                }?.value
            if (dir != null) mangaDirs.add(dir)
        }

        if (mangaDirs.isEmpty()) return emptySet()

        val allChapterDirs = mangaDirs.flatMap { it.chapterDirs }.distinct()

        return chapters
            .filter { chapter -> isChapterDirMatching(mangaTitle, chapter, allChapterDirs) }
            .mapTo(HashSet()) { it.id }
    }

    /**
     * Returns true if the chapter is downloaded.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param chapterUrl the url of the chapter to query.
     * @param mangaTitle the title of the manga to query.
     * @param sourceId the id of the source of the chapter.
     */
    fun isChapterDownloaded(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        sourceId: Long,
    ): Boolean {
        renewCache()

        val targetMangaDirName = provider.getMangaDirName(mangaTitle)
        val validMangaName = DiskUtil.buildValidFilename(mangaTitle)

        val tempChapter = Chapter.create().copy(name = chapterName, scanlator = chapterScanlator, url = chapterUrl)

        for ((_, sourceDir) in rootDownloadsDir.sourceDirs) {
            val mangaDir = sourceDir.mangaDirs[targetMangaDirName]
                ?: sourceDir.mangaDirs[validMangaName]
                ?: sourceDir.mangaDirs.entries.firstOrNull {
                    it.key.equals(targetMangaDirName, ignoreCase = true) ||
                        it.key.equals(validMangaName, ignoreCase = true) ||
                        it.key.equals(mangaTitle, ignoreCase = true)
                }?.value

            if (mangaDir != null && isChapterDirMatching(mangaTitle, tempChapter, mangaDir.chapterDirs)) {
                return true
            }
        }

        return false
    }

    /**
     * Returns true if the chapter is downloaded in any source other than currentSourceId.
     */
    fun isChapterDownloadedInAnyOtherSource(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        currentSourceId: Long,
    ): Boolean {
        renewCache()

        val targetMangaDirName = provider.getMangaDirName(mangaTitle)
        val validMangaName = DiskUtil.buildValidFilename(mangaTitle)

        val tempChapter = Chapter.create().copy(name = chapterName, scanlator = chapterScanlator, url = chapterUrl)

        for ((sourceId, sourceDir) in rootDownloadsDir.sourceDirs) {
            if (sourceId == currentSourceId) continue
            val mangaDir = sourceDir.mangaDirs[targetMangaDirName]
                ?: sourceDir.mangaDirs[validMangaName]
                ?: sourceDir.mangaDirs.entries.firstOrNull {
                    it.key.equals(targetMangaDirName, ignoreCase = true) ||
                        it.key.equals(validMangaName, ignoreCase = true) ||
                        it.key.equals(mangaTitle, ignoreCase = true)
                }?.value

            if (mangaDir != null && isChapterDirMatching(mangaTitle, tempChapter, mangaDir.chapterDirs)) {
                return true
            }
        }

        return false
    }

    /**
     * Returns the amount of downloaded chapters.
     */
    fun getTotalDownloadCount(): Int {
        renewCache()

        // The disk index stores a folder shared by several ids once per id
        return rootDownloadsDir.sourceDirs.values.distinctBy { it.dir?.uri }.sumOf { sourceDir ->
            sourceDir.mangaDirs.values.sumOf { mangaDir ->
                mangaDir.chapterDirs.size
            }
        }
    }

    /**
     * Returns the amount of downloaded chapters for a manga.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCount(manga: Manga): Int {
        renewCache()

        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)]
            if (mangaDir != null) {
                return mangaDir.chapterDirs.size
            }
        }
        return 0
    }

    /**
     * Returns the amount of downloaded chapters for each of the given manga, keyed by manga id.
     *
     * @param manga the manga to check.
     */
    fun getDownloadCounts(manga: List<Manga>): Map<Long, Int> {
        renewCache()

        val sourceDirs = rootDownloadsDir.sourceDirs
        return manga.associate {
            val mangaDirs = sourceDirs[it.source]?.mangaDirs
            // Building the directory name is the costly part, so it's skipped for sources without downloads
            val mangaDir = if (mangaDirs.isNullOrEmpty()) {
                null
            } else {
                mangaDirs[provider.getMangaDirName(it.title)]
            }
            it.id to (mangaDir?.chapterDirs?.size ?: 0)
        }
    }

    /**
     * Adds a chapter that has just been download to this cache.
     *
     * @param chapterDirName the downloaded chapter's directory name.
     * @param mangaUniFile the directory of the manga.
     * @param manga the manga of the chapter.
     */
    suspend fun addChapter(chapterDirName: String, mangaUniFile: UniFile, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            // Retrieve the cached source directory or cache a new one
            var sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir == null) {
                val source = sourceManager.get(manga.source) ?: return
                val sourceUniFile = provider.findSourceDir(source) ?: return
                sourceDir = SourceDirectory(sourceUniFile)
                rootDownloadsDir.sourceDirs += manga.source to sourceDir
            }

            // Retrieve the cached manga directory or cache a new one
            val mangaDirName = provider.getMangaDirName(manga.title)
            var mangaDir = sourceDir.mangaDirs[mangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += mangaDirName to mangaDir
            }

            // Save the chapter directory
            mangaDir.chapterDirs += chapterDirName
        }

        notifyChanges()
    }

    /**
     * Removes a chapter that has been deleted from this cache.
     *
     * @param chapter the chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapter(chapter: Chapter, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            mangaDir.chapterDirs -=
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url).toSet()
        }

        notifyChanges()
    }

    /**
     * Removes a list of chapters that have been deleted from this cache.
     *
     * @param chapters the list of chapter to remove.
     * @param manga the manga of the chapter.
     */
    suspend fun removeChapters(chapters: List<Chapter>, manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDir = sourceDir.mangaDirs[provider.getMangaDirName(manga.title)] ?: return
            mangaDir.chapterDirs -= chapters
                .flatMap { provider.getValidChapterDirNames(it.name, it.scanlator, it.url) }
                .toSet()
        }

        notifyChanges()
    }

    /**
     * Removes a manga that has been deleted from this cache.
     *
     * @param manga the manga to remove.
     */
    suspend fun removeManga(manga: Manga) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val mangaDirName = provider.getMangaDirName(manga.title)
            if (sourceDir.mangaDirs.containsKey(mangaDirName)) {
                sourceDir.mangaDirs -= mangaDirName
            }
        }

        notifyChanges()
    }

    /**
     * Renames a manga in this cache.
     *
     * @param manga the manga being renamed.
     * @param mangaUniFile the manga's new directory.
     * @param newTitle the manga's new title.
     */
    suspend fun renameManga(manga: Manga, mangaUniFile: UniFile, newTitle: String) {
        rootDownloadsDirMutex.withLock {
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source] ?: return
            val oldMangaDirName = provider.getMangaDirName(manga.title)
            var oldChapterDirs: Set<String>? = null
            // Save the old name's cached chapter dirs
            if (sourceDir.mangaDirs.containsKey(oldMangaDirName)) {
                oldChapterDirs = sourceDir.mangaDirs[oldMangaDirName]?.chapterDirs
                sourceDir.mangaDirs -= oldMangaDirName
            }

            // Retrieve/create the cached manga directory for new name
            val newMangaDirName = provider.getMangaDirName(newTitle)
            var mangaDir = sourceDir.mangaDirs[newMangaDirName]
            if (mangaDir == null) {
                mangaDir = MangaDirectory(mangaUniFile)
                sourceDir.mangaDirs += newMangaDirName to mangaDir
            }

            // Add the old chapters to new name's cache
            if (!oldChapterDirs.isNullOrEmpty()) {
                mangaDir.chapterDirs += oldChapterDirs
            }
        }

        notifyChanges()
    }

    suspend fun refreshManga(manga: Manga, mangaDir: UniFile) {
        val chapterDirs = withContext(Dispatchers.IO) {
            mangaDir.listFiles().orEmpty()
                .mapNotNull {
                    when {
                        it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                        it.isDirectory -> it.name
                        it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                        else -> null
                    }
                }
                .toSet()
        }

        rootDownloadsDirMutex.withLock {
            val mangaDirName = provider.getMangaDirName(manga.title)
            val validMangaName = DiskUtil.buildValidFilename(manga.title)

            var targetMangaDir: MangaDirectory? = null
            val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
            if (sourceDir != null) {
                targetMangaDir = sourceDir.mangaDirs[mangaDirName] ?: sourceDir.mangaDirs[validMangaName]
            }

            if (targetMangaDir == null) {
                for ((_, srcDir) in rootDownloadsDir.sourceDirs) {
                    val found = srcDir.mangaDirs[mangaDirName] ?: srcDir.mangaDirs[validMangaName]
                    if (found != null) {
                        targetMangaDir = found
                        break
                    }
                }
            }

            if (targetMangaDir != null) {
                targetMangaDir.chapterDirs = chapterDirs
            } else if (sourceDir != null) {
                val newMangaDir = MangaDirectory(mangaDir).apply { this.chapterDirs = chapterDirs }
                sourceDir.mangaDirs += mangaDirName to newMangaDir
            }
        }

        _changes.trySend(Unit)
    }

    fun setMangaChapterDirs(manga: Manga, chapterDirs: Set<String>) {
        val mangaDirName = provider.getMangaDirName(manga.title)
        val validMangaName = DiskUtil.buildValidFilename(manga.title)

        var targetMangaDir: MangaDirectory? = null
        val sourceDir = rootDownloadsDir.sourceDirs[manga.source]
        if (sourceDir != null) {
            targetMangaDir = sourceDir.mangaDirs[mangaDirName] ?: sourceDir.mangaDirs[validMangaName]
        }

        if (targetMangaDir == null) {
            for ((_, srcDir) in rootDownloadsDir.sourceDirs) {
                val found = srcDir.mangaDirs[mangaDirName] ?: srcDir.mangaDirs[validMangaName]
                if (found != null) {
                    targetMangaDir = found
                    break
                }
            }
        }

        if (targetMangaDir != null) {
            targetMangaDir.chapterDirs = chapterDirs
        }

        _changes.trySend(Unit)
    }

    suspend fun removeSource(source: Source) {
        rootDownloadsDirMutex.withLock {
            rootDownloadsDir.sourceDirs -= source.id
        }

        notifyChanges()
    }

    fun invalidateCache(showNotification: Boolean = false) {
        lastRenew = 0L
        renewalJob?.cancel()
        renewCache(deleteDiskCache = true, showNotification = showNotification)
    }

    /**
     * Renews the downloads cache.
     */
    private fun renewCache(deleteDiskCache: Boolean = false, showNotification: Boolean = false) {
        // Avoid renewing cache if in the process nor too often
        if (lastRenew + renewInterval >= System.currentTimeMillis() || renewalJob?.isActive == true) {
            return
        }

        renewalJob = scope.launch(Dispatchers.IO) {
            // The disk index is older than this scan and would replace it if read afterwards
            initJob.join()
            if (deleteDiskCache) diskCacheFile.delete()

            if (showNotification) {
                _isInitializing.emit(true)
            }

            // Try to wait until extensions and sources have loaded
            var sources = emptyList<Source>()
            withTimeoutOrNull(30.seconds) {
                sources = getSources()
            }

            // A stored stub can share its name with an installed source, so a folder can belong to several ids
            val sourceIdsByDirName = sources.groupBy({ provider.getSourceDirName(it).lowercase() }, { it.id })

            rootDownloadsDirMutex.withLock {
                val downloadsDir = storageManager.getDownloadsDirectory()
                val tempDir = storageManager.getTempDirectory()
                val dropDir = storageManager.getDropDirectory()
                val updatedRootDir = RootDirectory(downloadsDir)

                val downloadSourceFiles = downloadsDir?.listFiles().orEmpty().toList()
                val tempSourceFiles = tempDir?.listFiles().orEmpty().toList()
                val dropSourceFiles = dropDir?.listFiles().orEmpty().toList()
                val allSourceFiles = (downloadSourceFiles + tempSourceFiles + dropSourceFiles)
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }

                updatedRootDir.sourceDirs = allSourceFiles
                    .flatMap { dir ->
                        val sourceDir = SourceDirectory(dir)
                        sourceIdsByDirName[dir.name!!.lowercase()].orEmpty().map { it to sourceDir }
                    }
                    .toMap()

                updatedRootDir.sourceDirs.values.distinct().map { sourceDir ->
                    async {
                        val sourceDirName = sourceDir.dir?.name?.lowercase().orEmpty()
                        val downloadsSourceDir = downloadsDir?.listFiles().orEmpty().firstOrNull { it.name?.equals(sourceDirName, ignoreCase = true) == true }
                        val tempSourceDir = tempDir?.listFiles().orEmpty().firstOrNull { it.name?.equals(sourceDirName, ignoreCase = true) == true }
                        val dropSourceDir = dropDir?.listFiles().orEmpty().firstOrNull { it.name?.equals(sourceDirName, ignoreCase = true) == true }

                        val downloadMangaFiles = downloadsSourceDir?.listFiles().orEmpty().toList()
                        val tempMangaFiles = tempSourceDir?.listFiles().orEmpty().toList()
                        val dropMangaFiles = dropSourceDir?.listFiles().orEmpty().toList()
                        val allMangaFiles = (downloadMangaFiles + tempMangaFiles + dropMangaFiles)
                            .filter { it.isDirectory && !it.name.isNullOrBlank() }

                        sourceDir.mangaDirs = allMangaFiles
                            .associate { it.name!! to MangaDirectory(it) }

                        sourceDir.mangaDirs.values.forEach { mangaDir ->
                            val chapterDirs = mangaDir.dir?.listFiles().orEmpty()
                                .mapNotNull {
                                    when {
                                        // Ignore incomplete downloads
                                        it.name?.endsWith(Downloader.TMP_DIR_SUFFIX) == true -> null
                                        // Folder of images
                                        it.isDirectory -> it.name
                                        // CBZ files
                                        it.isFile && it.extension == "cbz" -> it.nameWithoutExtension
                                        // Anything else is irrelevant
                                        else -> null
                                    }
                                }
                                .toSet()

                            mangaDir.chapterDirs = chapterDirs
                        }
                    }
                }
                    .awaitAll()

                rootDownloadsDir = updatedRootDir
            }

            _isInitializing.emit(false)
        }.also {
            it.invokeOnCompletion(onCancelling = true) { exception ->
                if (exception != null && exception !is CancellationException) {
                    logcat(LogPriority.ERROR, exception) { "DownloadCache: failed to create cache" }
                }
                // A cancelled renewal (e.g. from invalidateCache) must not hold off the one replacing it
                if (exception !is CancellationException) lastRenew = System.currentTimeMillis()
                notifyChanges()
            }
        }

        // Mainly to notify the indexing notifier UI
        notifyChanges()
    }

    private suspend fun getSources(): List<Source> {
        return sourceManager.getOnlineSources() + sourceManager.getStubSources()
    }

    private fun notifyChanges() {
        _changes.trySend(Unit)
        updateDiskCache()
    }

    private var updateDiskCacheJob: Job? = null
    private fun updateDiskCache() {
        updateDiskCacheJob?.cancel()
        updateDiskCacheJob = scope.launch(Dispatchers.IO) {
            delay(1.seconds)
            val bytes = rootDownloadsDirMutex.withLock {
                ProtoBuf.encodeToByteArray(rootDownloadsDir)
            }
            ensureActive()
            try {
                diskCacheFile.writeBytes(bytes)
            } catch (e: Throwable) {
                logcat(
                    priority = LogPriority.ERROR,
                    throwable = e,
                    message = { "Failed to write disk cache file" },
                )
            }
        }
    }
}

/**
 * Class to store the files under the root downloads directory.
 */
@Serializable
private class RootDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile
    var sourceDirs: Map<Long, SourceDirectory> = mapOf(),
)

/**
 * Class to store the files under a source directory.
 */
@Serializable
private class SourceDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    @Volatile
    var mangaDirs: Map<String, MangaDirectory> = mapOf(),
)

/**
 * Class to store the files under a manga directory.
 */
@Serializable
private class MangaDirectory(
    @Serializable(with = UniFileAsStringSerializer::class)
    val dir: UniFile?,
    // Replaced rather than changed in place, so the lock-free lookups always read a consistent set
    @Volatile
    var chapterDirs: Set<String> = emptySet(),
)

private object UniFileAsStringSerializer : KSerializer<UniFile?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UniFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UniFile?) {
        return if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.uri.toString())
        }
    }

    override fun deserialize(decoder: Decoder): UniFile? {
        return if (decoder.decodeNotNullMark()) {
            UniFile.fromUri(Injekt.get<Context>(), decoder.decodeString().toUri())
        } else {
            decoder.decodeNull()
        }
    }
}
