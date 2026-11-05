package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import mihon.data.cache.ChapterCache
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

/**
 * Loader used to retrieve the [PageLoader] for a given chapter.
 */
class ChapterLoader(
    private val context: Context,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val chapterCache: ChapterCache,
    private val appScope: CoroutineScope,
    private val canLoadAhead: () -> Boolean,
    private val manga: Manga,
    private val source: Source,
) {

    /**
     * Assigns the chapter's page loader and loads the its pages. Returns immediately if the chapter
     * is already loaded.
     */
    suspend fun loadChapter(chapter: ReaderChapter) {
        if (chapterIsReady(chapter)) {
            return
        }

        chapter.state = ReaderChapter.State.Loading
        withContext(Dispatchers.IO) {
            logcat(LogPriority.INFO) { "[OpenChapter Stage 1] Loading chapter '${chapter.chapter.name}' (id=${chapter.chapter.id}) for manga '${manga.title}'" }
            try {
                val loader = getPageLoader(chapter)
                chapter.pageLoader = loader

                val pages = loader.getPages()
                    .onEach { it.chapter = chapter }

                if (pages.isEmpty()) {
                    throw Exception(context.stringResource(MR.strings.page_list_empty_error))
                }

                // If the chapter is partially read, set the starting page to the last the user read
                // otherwise use the requested page.
                if (!chapter.chapter.read) {
                    chapter.requestedPage = chapter.chapter.lastPageRead
                }

                chapter.state = ReaderChapter.State.Loaded(pages)
                logcat(LogPriority.INFO) { "[OpenChapter Stage 4] Successfully loaded ${pages.size} pages for chapter '${chapter.chapter.name}'" }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "[OpenChapter Stage Error] Failed to load chapter '${chapter.chapter.name}'" }
                chapter.state = ReaderChapter.State.Error(e)
                throw e
            }
        }
    }

    /**
     * Checks [chapter] to be loaded based on present pages and loader in addition to state.
     */
    private fun chapterIsReady(chapter: ReaderChapter): Boolean {
        return chapter.state is ReaderChapter.State.Loaded && chapter.pageLoader != null
    }

    /**
     * Returns the page loader to use for this [chapter].
     */
    private suspend fun getPageLoader(chapter: ReaderChapter): PageLoader {
        val dbChapter = chapter.chapter
        logcat(LogPriority.INFO) { "[OpenChapter Stage 2] Checking physical file on disk for chapter '${dbChapter.name}'" }
        val chapterDir = downloadProvider.findChapterDir(
            dbChapter.name,
            dbChapter.scanlator,
            dbChapter.url,
            manga.title,
            source,
        )
        val fileExists = chapterDir != null && chapterDir.exists()

        if (fileExists) {
            logcat(LogPriority.INFO) { "[OpenChapter Stage 3] Physical file found at '${chapterDir?.uri}'. Using DownloadPageLoader" }
            return DownloadPageLoader(
                chapter,
                manga,
                source,
                downloadManager,
                downloadProvider,
            )
        }

        logcat(LogPriority.WARN) { "[OpenChapter Stage 3] Physical file NOT found on disk for '${dbChapter.name}'. Resetting downloaded status in DB/cache and blocking network stream" }
        downloadManager.clearChapterDownloadStatus(dbChapter, manga)
        downloadManager.deleteChapters(listOf(dbChapter), manga, source)

        return when {
            source is LocalSource -> source.getFormat(chapter.chapter.toSChapter()).let { format ->
                when (format) {
                    is Format.Directory -> DirectoryPageLoader(format.file)
                    is Format.Archive -> ArchivePageLoader(format.file.archiveReader(context))
                    is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
                }
            }
            source is HttpSource -> error(context.stringResource(MR.strings.chapter_not_found))
            source is StubSource -> error(context.stringResource(MR.strings.source_not_installed, source.toString()))
            else -> error(context.stringResource(MR.strings.loader_not_implemented_error))
        }
    }
}
