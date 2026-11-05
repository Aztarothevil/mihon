package eu.kanade.tachiyomi.data.coil

import androidx.core.net.toUri
import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getOrDefault
import coil3.request.Options
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.coil.MangaCoverFetcher.Companion.USE_CUSTOM_COVER_KEY
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.await
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import eu.kanade.domain.chapter.model.toSChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.io.IOException
import eu.kanade.tachiyomi.source.Source as SourceModel

/**
 * A [Fetcher] that fetches cover image for [Manga] object.
 *
 * It uses [Manga.thumbnailUrl] if custom cover is not set by the user.
 * Disk caching for library items is handled by [CoverCache], otherwise
 * handled by Coil's [DiskCache].
 *
 * Available request parameter:
 * - [USE_CUSTOM_COVER_KEY]: Use custom cover if set by user, default is true
 */
class MangaCoverFetcher(
    private val url: String?,
    private val isLibraryManga: Boolean,
    private val options: Options,
    private val coverFileLazy: Lazy<File?>,
    private val customCoverFileLazy: Lazy<File>,
    private val diskCacheKeyLazy: Lazy<String>,
    private val getSource: suspend () -> SourceModel?,
    private val callFactoryLazy: Lazy<Call.Factory>,
    private val imageLoader: ImageLoader,
    private val getMangaTitle: (suspend () -> String?)? = null,
    private val downloadProviderLazy: Lazy<DownloadProvider>? = null,
    private val getMangaId: (() -> Long?)? = null,
    private val chapterRepositoryLazy: Lazy<ChapterRepository>? = null,
) : Fetcher {

    private val diskCacheKey: String
        get() = diskCacheKeyLazy.value

    override suspend fun fetch(): FetchResult {
        // Use custom cover if exists, which only library manga can have
        val useCustomCover = options.extras.getOrDefault(USE_CUSTOM_COVER_KEY)
        if (useCustomCover && isLibraryManga) {
            val customCoverFile = customCoverFileLazy.value
            if (customCoverFile.exists()) {
                saveToMangaDirectory(customCoverFile)
                return fileLoader(customCoverFile)
            }
        }

        return when (getResourceType(url)) {
            Type.File -> {
                val file = File(url!!.substringAfter("file://"))
                saveToMangaDirectory(file)
                fileLoader(file)
            }
            Type.URI -> fileUriLoader(url!!)
            // Requests for the same cover at different sizes or through Manga and MangaCover would each download it
            Type.URL, null -> concurrentRequests.apply(diskCacheKey) { httpLoader() }
        }
    }

    private fun fileLoader(file: File): FetchResult {
        return SourceFetchResult(
            source = ImageSource(
                file = file.toOkioPath(),
                fileSystem = FileSystem.SYSTEM,
                diskCacheKey = diskCacheKey,
            ),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private fun fileUriLoader(uri: String): FetchResult {
        val source = UniFile.fromUri(options.context, uri.toUri())!!
            .openInputStream()
            .source()
            .buffer()
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM),
            mimeType = "image/*",
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun httpLoader(): FetchResult {
        val source = getSource()
        val mangaTitle = getMangaTitle?.invoke()

        // 1. Check if cover.jpg already exists in manga download directory
        if (mangaTitle != null && downloadProviderLazy != null && source != null) {
            val localMangaCover = downloadProviderLazy.value.findMangaCover(mangaTitle, source)
            if (localMangaCover != null) {
                return fileUriLoader(localMangaCover.uri.toString())
            }
        }

        // 2. Check if cover exists in CoverCache
        val libraryCoverCacheFile = if (isLibraryManga) {
            coverFileLazy.value
        } else {
            null
        }

        if (libraryCoverCacheFile?.exists() == true) {
            saveToMangaDirectory(libraryCoverCacheFile)
            return fileLoader(libraryCoverCacheFile)
        }

        // 3. If URL points to otakuteca.com (broken server), skip attempting direct fetch and extract first chapter cover
        val isOtakutecaUrl = url?.contains("otakuteca.com", ignoreCase = true) == true
        if (isOtakutecaUrl) {
            val chapterCover = searchAndSaveFirstChapterCover(getMangaId?.invoke(), mangaTitle, source)
            if (chapterCover != null) {
                return chapterCover
            }
        }

        var networkException: Exception? = null
        val allowNetworkFetch = options.networkCachePolicy.writeEnabled && options.networkCachePolicy != coil3.request.CachePolicy.DISABLED

        // 4. Try fetching directly from network source if URL is available and network fetch is allowed
        if (!url.isNullOrBlank() && !isOtakutecaUrl && allowNetworkFetch) {
            try {
                val response = executeNetworkRequest()
                val responseBody = checkNotNull(response.body) { "Null response source" }

                val responseCoverCache = writeResponseToCoverCache(response, libraryCoverCacheFile)
                if (responseCoverCache != null) {
                    saveToMangaDirectory(responseCoverCache)
                    return fileLoader(responseCoverCache)
                }

                if (mangaTitle != null && downloadProviderLazy != null && source != null) {
                    response.peekBody(Long.MAX_VALUE).byteStream().use { input ->
                        val savedCover = downloadProviderLazy.value.saveMangaCover(mangaTitle, source, input)
                        if (savedCover != null) {
                            return fileUriLoader(savedCover.uri.toString())
                        }
                    }
                }

                return SourceFetchResult(
                    source = ImageSource(source = responseBody.source(), fileSystem = FileSystem.SYSTEM),
                    mimeType = "image/*",
                    dataSource = if (response.cacheResponse != null) DataSource.DISK else DataSource.NETWORK,
                )
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to fetch cover directly from $url" }
                networkException = e
            }
        }

        // 5. Fallback: Extract cover from first page of first chapter
        val chapterCover = searchAndSaveFirstChapterCover(getMangaId?.invoke(), mangaTitle, source)
        if (chapterCover != null) {
            return chapterCover
        }

        // 6. If all fetch attempts failed
        if (url.isNullOrBlank()) {
            error("No cover specified")
        }

        throw networkException ?: error("Failed to fetch cover")
    }

    private suspend fun searchAndSaveFirstChapterCover(mangaId: Long?, mangaTitle: String?, source: SourceModel?): FetchResult? = withContext(Dispatchers.IO) {
        if (downloadProviderLazy == null || source == null || source is StubSource || chapterRepositoryLazy == null || mangaId == null || mangaTitle.isNullOrBlank()) return@withContext null
        return@withContext try {
            val chapters = chapterRepositoryLazy.value.getChapterByMangaId(mangaId)
                .sortedBy { it.chapterNumber }
            if (chapters.isEmpty()) return@withContext null

            for (chapter in chapters.take(3)) {
                val pages = runCatching { source.getPageList(chapter.toSChapter()) }.getOrNull() ?: continue
                val firstPage = pages.firstOrNull() ?: continue

                var imageUrl = firstPage.imageUrl
                if (imageUrl.isNullOrBlank() && source is HttpSource) {
                    imageUrl = runCatching { source.getImageUrl(firstPage) }.getOrNull()
                }

                val bytes = if (source is HttpSource) {
                    val response = runCatching { source.getImage(firstPage) }.getOrNull() ?: continue
                    if (!response.isSuccessful) {
                        response.close()
                        continue
                    }
                    response.body.bytes()
                } else if (!imageUrl.isNullOrBlank()) {
                    val request = Request.Builder().url(imageUrl).build()
                    val response = runCatching { callFactoryLazy.value.newCall(request).await() }.getOrNull() ?: continue
                    if (!response.isSuccessful) {
                        response.close()
                        continue
                    }
                    response.body.bytes()
                } else {
                    continue
                }

                if (!isValidImage(bytes)) continue

                val savedCover = downloadProviderLazy.value.saveMangaCover(mangaTitle, source, bytes.inputStream())
                if (savedCover != null) {
                    return@withContext fileUriLoader(savedCover.uri.toString())
                }

                return@withContext SourceFetchResult(
                    source = ImageSource(source = okio.Buffer().write(bytes), fileSystem = FileSystem.SYSTEM),
                    mimeType = "image/*",
                    dataSource = DataSource.NETWORK,
                )
            }
            null
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to extract first chapter cover for $mangaTitle" }
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

    private suspend fun executeNetworkRequest(): Response {
        val source = getSource() as? HttpSource
        val client = source?.client ?: callFactoryLazy.value
        val response = client.newCall(newRequest(source)).await()
        if (!response.isSuccessful && response.code != HTTP_NOT_MODIFIED) {
            response.close()
            throw HttpException(response.code)
        }
        return response
    }

    private fun newRequest(source: HttpSource?): Request {
        val request = Request.Builder().apply {
            url(url!!)

            val sourceHeaders = source?.headers
            if (sourceHeaders != null) {
                headers(sourceHeaders)
            }
        }

        when {
            options.networkCachePolicy.readEnabled -> {
                // don't take up okhttp cache
                request.cacheControl(CACHE_CONTROL_NO_STORE)
            }
            else -> {
                // This causes the request to fail with a 504 Unsatisfiable Request.
                request.cacheControl(CACHE_CONTROL_NO_NETWORK_NO_CACHE)
            }
        }

        return request.build()
    }

    private suspend fun saveToMangaDirectory(cacheFile: File?) {
        if (cacheFile == null || !cacheFile.exists() || getMangaTitle == null || downloadProviderLazy == null) return
        try {
            val source = getSource() ?: return
            val mangaTitle = getMangaTitle.invoke() ?: return
            cacheFile.inputStream().use { input ->
                downloadProviderLazy.value.saveMangaCover(mangaTitle, source, input)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to save cover to manga directory" }
        }
    }

    private fun writeResponseToCoverCache(response: Response, cacheFile: File?): File? {
        if (cacheFile == null || !options.diskCachePolicy.writeEnabled) return null
        return try {
            response.peekBody(Long.MAX_VALUE).source().use { input ->
                writeSourceToCoverCache(input, cacheFile)
            }
            cacheFile.takeIf { it.exists() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to write response data to cover cache ${cacheFile.name}" }
            null
        }
    }

    private fun writeSourceToCoverCache(input: Source, cacheFile: File) {
        cacheFile.parentFile?.mkdirs()
        cacheFile.delete()
        try {
            cacheFile.sink().buffer().use { output ->
                output.writeAll(input)
            }
        } catch (e: Exception) {
            cacheFile.delete()
            throw e
        }
    }

    private fun getResourceType(cover: String?): Type? {
        return when {
            cover.isNullOrEmpty() -> null
            cover.startsWith("http", true) || cover.startsWith("Custom-", true) -> Type.URL
            cover.startsWith("/") || cover.startsWith("file://") -> Type.File
            cover.startsWith("content") -> Type.URI
            else -> null
        }
    }

    private enum class Type {
        File,
        URI,
        URL,
    }

    class MangaFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
        private val coverCache: CoverCache,
        private val sourceManager: SourceManager,
        private val downloadProviderLazy: Lazy<DownloadProvider>,
        private val chapterRepositoryLazy: Lazy<ChapterRepository>,
    ) : Fetcher.Factory<Manga> {

        override fun create(data: Manga, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                url = data.thumbnailUrl,
                isLibraryManga = data.favorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.thumbnailUrl) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.id) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                getSource = { sourceManager.getOrStub(data.source) },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
                getMangaTitle = { data.title },
                downloadProviderLazy = downloadProviderLazy,
                getMangaId = { data.id },
                chapterRepositoryLazy = chapterRepositoryLazy,
            )
        }
    }

    class MangaCoverFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
        private val coverCache: CoverCache,
        private val sourceManager: SourceManager,
        private val downloadProviderLazy: Lazy<DownloadProvider>,
        private val mangaRepositoryLazy: Lazy<MangaRepository>,
        private val chapterRepositoryLazy: Lazy<ChapterRepository>,
    ) : Fetcher.Factory<MangaCover> {

        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                url = data.url,
                isLibraryManga = data.isMangaFavorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.url) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.mangaId) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                getSource = { sourceManager.getOrStub(data.sourceId) },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
                getMangaTitle = { runCatching { mangaRepositoryLazy.value.getMangaById(data.mangaId).title }.getOrNull() },
                downloadProviderLazy = downloadProviderLazy,
                getMangaId = { data.mangaId },
                chapterRepositoryLazy = chapterRepositoryLazy,
            )
        }
    }

    companion object {
        val USE_CUSTOM_COVER_KEY = Extras.Key(true)

        private val CACHE_CONTROL_NO_STORE = CacheControl.Builder().noStore().build()
        private val CACHE_CONTROL_NO_NETWORK_NO_CACHE = CacheControl.Builder().noCache().onlyIfCached().build()

        private const val HTTP_NOT_MODIFIED = 304

        private val concurrentRequests = DeDupeConcurrentRequests<FetchResult>()
    }
}

