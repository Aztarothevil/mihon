package eu.kanade.tachiyomi.data.download

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.Hash.md5
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import java.io.IOException
import java.io.InputStream

/**
 * This class is used to provide the directories where the downloads should be saved.
 * It uses the following path scheme: /<root downloads dir>/<source name>/<manga>/<chapter>
 *
 * @param context the application context.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager,
    private val libraryPreferences: LibraryPreferences,
) {

    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()

    private val tempDir: UniFile?
        get() = storageManager.getTempDirectory()

    private val dropDir: UniFile?
        get() = storageManager.getDropDirectory()

    /**
     * Returns the download directory for a manga. For internal use only.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     * @param isFavorite whether the manga is in the library.
     */
    internal suspend fun getMangaDir(mangaTitle: String, source: Source, isFavorite: Boolean = true): Result<UniFile> =
        withContext(Dispatchers.IO) { createMangaDir(mangaTitle, source, isFavorite) }

    private fun createMangaDir(mangaTitle: String, source: Source, isFavorite: Boolean): Result<UniFile> {
        val baseDir = if (isFavorite) downloadsDir else tempDir
        if (baseDir == null) {
            logcat(LogPriority.ERROR) { "Failed to create download directory" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_download_directory)),
            )
        }

        val sourceDirName = getSourceDirName(source)
        val sourceDir = baseDir.createDirectory(sourceDirName)
        if (sourceDir == null) {
            val displayablePath = baseDir.displayablePath + "/$sourceDirName"
            logcat(LogPriority.ERROR) { "Failed to create source download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        val mangaDirName = getMangaDirName(mangaTitle)
        val mangaDir = sourceDir.createDirectory(mangaDirName)
        if (mangaDir == null) {
            val displayablePath = sourceDir.displayablePath + "/$mangaDirName"
            logcat(LogPriority.ERROR) { "Failed to create manga download directory: $displayablePath" }
            return Result.failure(
                IOException(context.stringResource(MR.strings.storage_failed_to_create_directory, displayablePath)),
            )
        }

        return Result.success(mangaDir)
    }

    /**
     * Returns the download directory for a source if it exists.
     *
     * @param source the source to query.
     */
    suspend fun findSourceDir(source: Source): UniFile? = withContext(Dispatchers.IO) {
        val sourceDirName = getSourceDirName(source)
        downloadsDir?.findFile(sourceDirName)
            ?: downloadsDir?.listFiles()?.firstOrNull { it.isDirectory && it.name?.equals(sourceDirName, ignoreCase = true) == true }
            ?: tempDir?.findFile(sourceDirName)
            ?: tempDir?.listFiles()?.firstOrNull { it.isDirectory && it.name?.equals(sourceDirName, ignoreCase = true) == true }
            ?: dropDir?.findFile(sourceDirName)
            ?: dropDir?.listFiles()?.firstOrNull { it.isDirectory && it.name?.equals(sourceDirName, ignoreCase = true) == true }
    }

    /**
     * Returns the download directory for a manga if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    suspend fun findMangaDir(mangaTitle: String, source: Source): UniFile? = withContext(Dispatchers.IO) {
        val mangaDirName = getMangaDirName(mangaTitle)
        val validMangaName = DiskUtil.buildValidFilename(mangaTitle)
        val sourceDirName = getSourceDirName(source)

        fun findInBaseDir(baseDir: UniFile?): UniFile? {
            if (baseDir == null || !baseDir.exists()) return null
            val sourceDir = baseDir.findFile(sourceDirName)
                ?: baseDir.listFiles()?.firstOrNull { it.isDirectory && it.name?.equals(sourceDirName, ignoreCase = true) == true }
            if (sourceDir != null) {
                val mangaDir = sourceDir.findFile(mangaDirName)
                    ?: sourceDir.findFile(validMangaName)
                    ?: sourceDir.listFiles()?.firstOrNull {
                        it.isDirectory && (it.name?.equals(mangaDirName, ignoreCase = true) == true ||
                            it.name?.equals(validMangaName, ignoreCase = true) == true ||
                            it.name?.equals(mangaTitle, ignoreCase = true) == true)
                    }
                if (mangaDir != null) return mangaDir
            }
            return null
        }

        fun findInFolder(folder: UniFile?): UniFile? {
            if (folder == null || !folder.exists()) return null
            return folder.findFile(mangaDirName)
                ?: folder.findFile(validMangaName)
                ?: folder.listFiles()?.firstOrNull {
                    it.isDirectory && (it.name?.equals(mangaDirName, ignoreCase = true) == true ||
                        it.name?.equals(validMangaName, ignoreCase = true) == true ||
                        it.name?.equals(mangaTitle, ignoreCase = true) == true)
                }
        }

        findInBaseDir(downloadsDir)?.let { return@withContext it }
        findInBaseDir(tempDir)?.let { return@withContext it }
        findInBaseDir(dropDir)?.let { return@withContext it }

        findInFolder(storageManager.getFinishedSourceDirectory())?.let { return@withContext it }
        findInFolder(storageManager.getLocalSourceDirectory())?.let { return@withContext it }

        downloadsDir?.listFiles()?.forEach { srcDir ->
            if (srcDir.isDirectory) {
                val found = findInFolder(srcDir)
                if (found != null) return@withContext found
            }
        }

        null
    }

    /**
     * Returns the cover image file for a manga from its download directory if it exists.
     *
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the manga.
     */
    suspend fun findMangaCover(mangaTitle: String, source: Source): UniFile? = withContext(Dispatchers.IO) {
        val mangaDir = findMangaDir(mangaTitle, source) ?: return@withContext null
        mangaDir.listFiles()
            ?.filter { it.isFile && (it.nameWithoutExtension?.equals("cover", ignoreCase = true) == true || it.nameWithoutExtension?.equals(".cover", ignoreCase = true) == true) }
            ?.firstOrNull { file ->
                val bytes = runCatching { file.openInputStream().use { it.readBytes() } }.getOrNull()
                if (bytes != null && isValidImage(bytes)) {
                    true
                } else {
                    file.delete()
                    false
                }
            }
    }

    /**
     * Saves the cover image to the manga's download directory if the directory exists.
     *
     * @param mangaTitle the title of the manga.
     * @param source the source of the manga.
     * @param inputStream the stream of the cover image.
     */
    suspend fun saveMangaCover(mangaTitle: String, source: Source, inputStream: InputStream): UniFile? = withContext(Dispatchers.IO) {
        val bytes = inputStream.readBytes()
        if (!isValidImage(bytes)) {
            return@withContext null
        }
        val mangaDir = getMangaDir(mangaTitle, source).getOrNull() ?: return@withContext null
        val targetFile = mangaDir.findFile("cover.jpg") ?: mangaDir.createFile("cover.jpg") ?: return@withContext null
        try {
            bytes.inputStream().use { input ->
                targetFile.openOutputStream().use { output ->
                    input.copyTo(output)
                }
            }
            DiskUtil.createNoMediaFile(mangaDir, context)
            targetFile
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save cover to manga directory" }
            null
        }
    }

    private fun isValidImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false

        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF

        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true

        // PNG: 89 50 4E 47
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true

        // GIF: GIF8 (47 49 46 38)
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return true

        // WEBP: RIFF ... WEBP
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46) {
            val b8 = bytes[8].toInt() and 0xFF
            val b9 = bytes[9].toInt() and 0xFF
            val b10 = bytes[10].toInt() and 0xFF
            val b11 = bytes[11].toInt() and 0xFF
            if (b8 == 0x57 && b9 == 0x45 && b10 == 0x42 && b11 == 0x50) return true
        }

        // BMP: BM
        if (b0 == 0x42 && b1 == 0x4D) return true

        // AVIF / HEIF: ftyp at bytes 4..7
        val b4 = bytes[4].toInt() and 0xFF
        val b5 = bytes[5].toInt() and 0xFF
        val b6 = bytes[6].toInt() and 0xFF
        val b7 = bytes[7].toInt() and 0xFF
        if (b4 == 0x66 && b5 == 0x74 && b6 == 0x79 && b7 == 0x70) return true

        // JXL: FF 0A
        if (b0 == 0xFF && b1 == 0x0A) return true

        // Fallback: BitmapFactory decode bounds
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the download directory for a chapter if it exists.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query
     * @param mangaTitle the title of the manga to query.
     * @param source the source of the chapter.
     */
    suspend fun findChapterDir(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        mangaTitle: String,
        source: Source,
    ): UniFile? = withContext(Dispatchers.IO) {
        val mangaDir = findMangaDir(mangaTitle, source) ?: return@withContext null
        val validNames = getValidChapterDirNames(chapterName, chapterScanlator, chapterUrl)

        for (name in validNames) {
            val file = mangaDir.findFile(name)
            if (file != null) return@withContext file
        }

        val sanitizedName = DiskUtil.buildValidFilename(chapterName)
        val fileByName = mangaDir.findFile(chapterName) ?: mangaDir.findFile(sanitizedName)
        if (fileByName != null) return@withContext fileByName

        val files = mangaDir.listFiles().orEmpty()
            .filter { !it.name.orEmpty().startsWith('.') && it.name != "cover.jpg" }

        val matchedFile = files.find { file ->
            val fileNameNoExt = file.nameWithoutExtension.orEmpty()
            val fileNameRaw = file.name.orEmpty()

            validNames.any { it.equals(fileNameNoExt, ignoreCase = true) || it.equals(fileNameRaw, ignoreCase = true) } ||
                fileNameNoExt.equals(chapterName, ignoreCase = true) ||
                fileNameNoExt.equals(sanitizedName, ignoreCase = true) ||
                fileNameRaw.equals(chapterName, ignoreCase = true) ||
                fileNameRaw.equals(sanitizedName, ignoreCase = true)
        }
        if (matchedFile != null) return@withContext matchedFile

        val chapterNum = ChapterRecognition.parseChapterNumber(mangaTitle, chapterName, -1.0)
        if (chapterNum >= 0) {
            val numMatch = files.find { file ->
                val fileNum = ChapterRecognition.parseChapterNumber(mangaTitle, file.nameWithoutExtension.orEmpty(), -1.0)
                fileNum >= 0 && fileNum == chapterNum
            }
            if (numMatch != null) return@withContext numMatch
        }

        null
    }

    /**
     * Returns a list of downloaded directories for the chapters that exist.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapter.
     * @param source the source of the chapter.
     */
    suspend fun findChapterDirs(
        chapters: List<Chapter>,
        manga: Manga,
        source: Source,
    ): Pair<UniFile?, List<UniFile>> = withContext(Dispatchers.IO) {
        val mangaDir = findMangaDir(manga.title, source) ?: return@withContext null to emptyList()
        val dirMap = chapters.mapNotNull { chapter ->
            val dir = findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
            dir?.let { chapter.id to it }
        }.toMap()
        mangaDir to dirMap.values.toList()
    }

    /**
     * Returns the ids of the chapters whose download directory exists.
     *
     * @param chapters the chapters to query.
     * @param manga the manga of the chapters.
     * @param source the source of the chapters.
     */
    suspend fun findDownloadedChapterIds(
        chapters: List<Chapter>,
        manga: Manga,
        source: Source,
    ): Set<Long> = withContext(Dispatchers.IO) {
        val mangaDir = findMangaDir(manga.title, source) ?: return@withContext emptySet()
        findChapterDirs(chapters, mangaDir).keys
    }

    /**
     * Returns the download directory of each chapter that has one, keyed by chapter id.
     *
     * A chapter is looked for under every name it may have been downloaded with, with the chapters looked up in
     * parallel. On a storage access framework directory each lookup is a content resolver query, so from
     * [LIST_CHAPTER_DIRS_MIN_CHAPTERS] chapters on the directory is listed once instead and the names are matched
     * against the listing, ignoring case as the lookups do. Plain file directories are always looked up by name, as
     * their listing can leave out files another app created.
     *
     * @param chapters the chapters to query.
     * @param mangaDir the download directory of the chapters' manga.
     */
    private suspend fun findChapterDirs(chapters: List<Chapter>, mangaDir: UniFile): Map<Long, UniFile> {
        val isDocumentTree = mangaDir.uri.scheme == ContentResolver.SCHEME_CONTENT
        if (isDocumentTree && chapters.size >= LIST_CHAPTER_DIRS_MIN_CHAPTERS) {
            val chapterDirs = mangaDir.listFiles().orEmpty()
                .filter { it.name != null }
                .associateByTo(sortedMapOf(String.CASE_INSENSITIVE_ORDER)) { it.name!! }
            return chapters
                .mapNotNull { chapter -> chapter.findDir { chapterDirs[it] }?.let { chapter.id to it } }
                .toMap()
        }
        return coroutineScope {
            chapters
                .map { chapter ->
                    async(chapterDirLookupDispatcher) { chapter.findDir(mangaDir::findFile)?.let { chapter.id to it } }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }
    }

    private fun Chapter.findDir(find: (String) -> UniFile?): UniFile? {
        return getValidChapterDirNames(name, scanlator, url).firstNotNullOfOrNull(find)
    }

    /**
     * Returns the download directory name for a source.
     *
     * @param source the source to query.
     */
    fun getSourceDirName(source: Source): String {
        return DiskUtil.buildValidFilename(
            source.toString(),
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    /**
     * Returns the download directory name for a manga.
     *
     * @param mangaTitle the title of the manga to query.
     */
    fun getMangaDirName(mangaTitle: String): String {
        return DiskUtil.buildValidFilename(
            mangaTitle,
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
        )
    }

    /**
     * Returns the chapter directory name for a chapter.
     *
     * @param chapterName the name of the chapter to query.
     * @param chapterScanlator scanlator of the chapter to query.
     * @param chapterUrl url of the chapter to query.
     */
    fun getChapterDirName(
        chapterName: String,
        chapterScanlator: String?,
        chapterUrl: String,
        disallowNonAsciiFilenames: Boolean = libraryPreferences.disallowNonAsciiFilenames.get(),
        enableChapterNameHash: Boolean = libraryPreferences.enableChapterNameHash.get(),
    ): String {
        return ChapterDirNameParts(chapterName, chapterScanlator, chapterUrl)
            .dirName(disallowNonAsciiFilenames, enableChapterNameHash)
    }

    /**
     * Returns list of names that might have been previously used as
     * the directory name for a chapter.
     * Add to this list if naming pattern ever changes.
     */
    private fun getLegacyChapterDirNames(parts: ChapterDirNameParts): Set<String> {
        val chapterNameV1 = DiskUtil.buildValidFilename(
            when {
                !parts.chapterScanlator.isNullOrBlank() -> "${parts.chapterScanlator}_${parts.chapterName}"
                else -> parts.chapterName
            },
        )

        // Generate all possible legacy directory name variations by combining
        // different states of non-ASCII filenames and chapter name hash settings.
        // This ensures that chapters downloaded under any past configuration
        // combination can still be successfully found.
        val booleanPairPermutation = listOf(false to false, false to true, true to false, true to true)
        val othersChapterDirNames = booleanPairPermutation
            .map { (disallowNonAsciiFilenames, enableChapterNameHash) ->
                parts.dirName(disallowNonAsciiFilenames, enableChapterNameHash)
            }
        val splitChapterDirNames = booleanPairPermutation
            .map { (disallowNonAsciiFilenames, enableChapterNameHash) ->
                parts.splitDirName(disallowNonAsciiFilenames, enableChapterNameHash, sanitizeScanlator = true)
            }

        // Scanlators weren't sanitized for a while. One with a "/" nested its folder, so it was never found anyway.
        val scanlator = parts.chapterScanlator
        val unsanitizedScanlatorChapterDirNames = if (scanlator != null && '/' !in scanlator) {
            booleanPairPermutation.map { (disallowNonAsciiFilenames, enableChapterNameHash) ->
                parts.splitDirName(disallowNonAsciiFilenames, enableChapterNameHash, sanitizeScanlator = false)
            }
        } else {
            emptyList()
        }

        return buildSet {
            // Chapter name without hash (unable to handle duplicate
            // chapter names)
            add(chapterNameV1)
            addAll(othersChapterDirNames)
            addAll(splitChapterDirNames)
            addAll(unsanitizedScanlatorChapterDirNames)
        }
    }

    fun isChapterDirNameChanged(oldChapter: Chapter, newChapter: Chapter): Boolean {
        return getChapterDirName(oldChapter.name, oldChapter.scanlator, oldChapter.url) !=
            getChapterDirName(newChapter.name, newChapter.scanlator, newChapter.url)
    }

    /**
     * Returns valid downloaded chapter directory names.
     *
     * @param chapter the domain chapter object.
     */
    fun getValidChapterDirNames(chapterName: String, chapterScanlator: String?, chapterUrl: String): List<String> {
        val parts = ChapterDirNameParts(chapterName, chapterScanlator, chapterUrl)
        val chapterDirName = parts.dirName(
            disallowNonAscii = libraryPreferences.disallowNonAsciiFilenames.get(),
            enableHash = libraryPreferences.enableChapterNameHash.get(),
        )
        val legacyChapterDirNames = getLegacyChapterDirNames(parts)

        return buildList {
            // Folder of images
            add(chapterDirName)
            // Archived chapters
            add("$chapterDirName.cbz")

            // any legacy names
            legacyChapterDirNames.forEach {
                add(it)
                add("$it.cbz")
            }
        }
    }
}

private const val LIST_CHAPTER_DIRS_MIN_CHAPTERS = 50

private const val CHAPTER_DIR_LOOKUP_PARALLELISM = 8

private val chapterDirLookupDispatcher = Dispatchers.IO.limitedParallelism(CHAPTER_DIR_LOOKUP_PARALLELISM)

/**
 * The parts of a chapter's directory name, each computed once however many name variations are built from them.
 */
private class ChapterDirNameParts(chapterName: String, val chapterScanlator: String?, private val chapterUrl: String) {

    /** The chapter name, or a placeholder when it's blank. */
    val chapterName = chapterName.ifBlank { "Chapter" }

    private val hashSuffix by lazy(LazyThreadSafetyMode.NONE) { "_${md5(chapterUrl).take(6)}" }

    private val sanitizedScanlator by lazy(LazyThreadSafetyMode.NONE) {
        chapterScanlator?.let { DiskUtil.buildValidFilename(it) }
    }

    private val validDirNames = arrayOfNulls<String>(2)

    private val validChapterNames = arrayOfNulls<String>(2)

    fun dirName(disallowNonAscii: Boolean, enableHash: Boolean): String {
        val index = if (disallowNonAscii) 1 else 0
        val dirName = validDirNames[index]
            // The scanlator and the name are made valid together, as every version before 0.21.0 did
            ?: DiskUtil.buildValidFilename(
                if (chapterScanlator.isNullOrBlank()) chapterName else "${chapterScanlator}_$chapterName",
                // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
                DiskUtil.MAX_FILE_NAME_BYTES - 11,
                disallowNonAscii,
            )
                .also { validDirNames[index] = it }
        return if (enableHash) dirName + hashSuffix else dirName
    }

    /**
     * The name 0.21.0 and 0.21.1 gave a chapter, which made only the chapter name valid and so differs for names
     * starting with a space or dot, long names, and non-ASCII scanlators.
     */
    fun splitDirName(disallowNonAscii: Boolean, enableHash: Boolean, sanitizeScanlator: Boolean): String {
        return buildString {
            if (!chapterScanlator.isNullOrBlank()) {
                // A "/" in the scanlator would otherwise nest the chapter in a folder of its own
                append(if (sanitizeScanlator) sanitizedScanlator else chapterScanlator)
                append("_")
            }
            append(validChapterName(disallowNonAscii))
            if (enableHash) append(hashSuffix)
        }
    }

    private fun validChapterName(disallowNonAscii: Boolean): String {
        val index = if (disallowNonAscii) 1 else 0
        return validChapterNames[index]
            // Subtract 7 bytes for hash and underscore, 4 bytes for .cbz
            ?: DiskUtil.buildValidFilename(chapterName, DiskUtil.MAX_FILE_NAME_BYTES - 11, disallowNonAscii)
                .also { validChapterNames[index] = it }
    }
}
