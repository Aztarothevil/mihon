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
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.online.HttpSource
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
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.io.IOException

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
    private val getSource: suspend () -> HttpSource?,
    private val callFactoryLazy: Lazy<Call.Factory>,
    private val imageLoader: ImageLoader,
    private val getMangaTitle: (suspend () -> String?)? = null,
    private val downloadProviderLazy: Lazy<DownloadProvider>? = null,
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

        // diskCacheKey is thumbnail_url
        if (url == null) error("No cover specified")
        return when (getResourceType(url)) {
            Type.File -> {
                val file = File(url.substringAfter("file://"))
                saveToMangaDirectory(file)
                fileLoader(file)
            }
            Type.URI -> fileUriLoader(url)
            // Requests for the same cover at different sizes or through Manga and MangaCover would each download it
            Type.URL -> concurrentRequests.apply(diskCacheKey) { httpLoader() }
            null -> error("Invalid image")
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

        // Check if cover exists in manga download directory
        if (mangaTitle != null && downloadProviderLazy != null && source != null) {
            val localMangaCover = downloadProviderLazy.value.findMangaCover(mangaTitle, source)
            if (localMangaCover != null) {
                return fileUriLoader(localMangaCover.uri.toString())
            }
        }

        val libraryCoverCacheFile = if (isLibraryManga) {
            coverFileLazy.value
        } else {
            null
        }

        if (libraryCoverCacheFile?.exists() == true) {
            saveToMangaDirectory(libraryCoverCacheFile)
            return fileLoader(libraryCoverCacheFile)
        }

        // Not in manga directory -> Fetch directly from network
        val response = executeNetworkRequest()
        val responseBody = checkNotNull(response.body) { "Null response source" }
        try {
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
            responseBody.close()
            throw e
        }
    }

    private suspend fun executeNetworkRequest(): Response {
        val source = getSource()
        val client = source?.client ?: callFactoryLazy.value
        val response = client.newCall(newRequest(source)).await()
        if (!response.isSuccessful && response.code != HTTP_NOT_MODIFIED) {
            response.close()
            throw IOException(response.message)
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
    ) : Fetcher.Factory<Manga> {

        override fun create(data: Manga, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                url = data.thumbnailUrl,
                isLibraryManga = data.favorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.thumbnailUrl) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.id) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                getSource = { sourceManager.get(data.source) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
                getMangaTitle = { data.title },
                downloadProviderLazy = downloadProviderLazy,
            )
        }
    }

    class MangaCoverFactory(
        private val callFactoryLazy: Lazy<Call.Factory>,
        private val coverCache: CoverCache,
        private val sourceManager: SourceManager,
        private val downloadProviderLazy: Lazy<DownloadProvider>,
        private val mangaRepositoryLazy: Lazy<MangaRepository>,
    ) : Fetcher.Factory<MangaCover> {

        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher {
            return MangaCoverFetcher(
                url = data.url,
                isLibraryManga = data.isMangaFavorite,
                options = options,
                coverFileLazy = lazy { coverCache.getCoverFile(data.url) },
                customCoverFileLazy = lazy { coverCache.getCustomCoverFile(data.mangaId) },
                diskCacheKeyLazy = lazy { imageLoader.components.key(data, options)!! },
                getSource = { sourceManager.get(data.sourceId) as? HttpSource },
                callFactoryLazy = callFactoryLazy,
                imageLoader = imageLoader,
                getMangaTitle = { runCatching { mangaRepositoryLazy.value.getMangaById(data.mangaId).title }.getOrNull() },
                downloadProviderLazy = downloadProviderLazy,
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
