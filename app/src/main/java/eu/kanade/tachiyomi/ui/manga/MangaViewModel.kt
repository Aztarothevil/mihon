package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.MigrateToFinished
import eu.kanade.domain.manga.interactor.MigrateToLocal
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.chaptersFiltered
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.SubscriberGate
import tachiyomi.core.common.util.lang.pausedWhileUnsubscribed
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterRemoteUpdate
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.chapter.service.calculateChapterGap
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.chapter.service.missingChaptersCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Archive
import tachiyomi.i18n.MR
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.isLocal
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class MangaViewModel(
    @Assisted private val mangaId: Long,
    @Assisted private val isFromSource: Boolean,
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
    trackPreferences: TrackPreferences,
    readerPreferences: ReaderPreferences,
    private val trackerManager: TrackerManager,
    private val trackChapter: TrackChapter,
    private val downloadManager: DownloadManager,
    private val downloadCache: DownloadCache,
    private val getMangaAndChapters: GetMangaWithChapters,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getAvailableScanlators: GetAvailableScanlators,
    private val getExcludedScanlators: GetExcludedScanlators,
    private val setExcludedScanlators: SetExcludedScanlators,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags,
    private val setReadStatus: SetReadStatus,
    private val updateChapter: UpdateChapter,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val getTracks: GetTracks,
    private val addTracks: AddTracks,
    private val setMangaCategories: SetMangaCategories,
    private val filterChaptersForDownload: FilterChaptersForDownload,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val syncChaptersWithSource: SyncChaptersWithSource,
    private val sourceManager: SourceManager,
    private val refreshTracks: RefreshTracks,
    private val coverCache: CoverCache,
    private val downloadProvider: DownloadProvider,
    private val chapterRepository: ChapterRepository,
    private val migrateToLocal: MigrateToLocal,
    private val migrateToFinished: MigrateToFinished,
    private val localSourceFileSystem: LocalSourceFileSystem,
    private val storageManager: StorageManager,
) : ViewModel() {

    private val subscriberGate = SubscriberGate()

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, isFromSource: Boolean): MangaViewModel
    }

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val successState: State.Success?
        get() = state.value as? State.Success

    val manga: Manga?
        get() = successState?.manga

    val source: Source?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = manga?.favorite ?: false

    private val allChapters: List<ChapterList.Item>?
        get() = successState?.chapters

    private val filteredChapters: List<ChapterList.Item>?
        get() = successState?.processedChapters

    val chapterSwipeStartAction = libraryPreferences.swipeToEndAction.get()
    val chapterSwipeEndAction = libraryPreferences.swipeToStartAction.get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead.get()

    private val skipFiltered by readerPreferences.skipFiltered.asState(viewModelScope)

    val isUpdateIntervalEnabled =
        LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateMangaRestrictions.get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedChapterIds = MutableStateFlow(emptySet<Long>())

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val isRefreshingData = MutableStateFlow(false)

    private val downloadStates = MutableStateFlow(emptyMap<Long, Download.State>())

    // Progress ticks often, so only the download indicators that read it recompose instead of every tick rebuilding
    // the chapter list
    private val downloadProgress = mutableStateMapOf<Long, Int>()

    // A finished chapter leaves the queue, and its last status change can be lost with it, so its state is left to
    // the queried item once it's no longer queued
    private val queuedDownloadStates = combine(downloadStates, downloadManager.queueState) { states, queue ->
        val queuedChapterIds = queue.mapTo(HashSet()) { it.chapter.id }
        states.filterKeys { it in queuedChapterIds }
    }

    private val hideMissingChapters = libraryPreferences.hideMissingChapters.get()

    private var hasPromptedToAddBefore = false

    private val defaultChapterFlagsJob = viewModelScope.launch {
        val manga = getMangaAndChapters.awaitManga(mangaId)
        if (!manga.favorite) {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    private val mangaAndChapters = combine(
        flow {
            // So an entry outside the library isn't shown with its old chapter settings first
            defaultChapterFlagsJob.join()
            emitAll(getMangaAndChapters.subscribe(mangaId, applyScanlatorFilter = true))
        }
            .distinctUntilChanged(),
        downloadCache.changes.pausedWhileUnsubscribed(),
        downloadManager.queueState.pausedWhileUnsubscribed(),
    ) { (manga, chapters), _, _ ->
        MangaAndChapters(
            manga = manga,
            source = sourceManager.getOrStub(manga.source),
            chapters = chapters.toChapterListItems(manga),
        )
    }

    private val scanlators = combine(
        getAvailableScanlators.subscribe(mangaId).distinctUntilChanged(),
        getExcludedScanlators.subscribe(mangaId).distinctUntilChanged(),
        ::Pair,
    )

    private val trackers = flow { emit(sourceManager.getOrStub(getMangaAndChapters.awaitManga(mangaId).source)) }
        .flatMapLatest { source ->
            combine(
                getTracks.subscribe(mangaId).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInTrackersFlow(),
            ) { mangaTracks, loggedInTrackers ->
                // Show only if the service supports this manga's source
                val supportedTrackers = loggedInTrackers.filter { (it as? EnhancedTracker)?.accept(source) ?: true }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = mangaTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
        }
        .distinctUntilChanged()
        .onStart { emit(0 to false) }

    val state: StateFlow<State> = combine(
        mangaAndChapters,
        scanlators,
        trackers,
        combine(selectedChapterIds, queuedDownloadStates.pausedWhileUnsubscribed(), ::Pair),
        combine(dialog, isRefreshingData, ::Pair),
    ) {
            mangaAndChapters,
            (availableScanlators, excludedScanlators),
            (trackingCount, hasLoggedInTrackers),
            (selectedIds, downloads),
            (dialog, isRefreshingData),
        ->
        State.Success(
            manga = mangaAndChapters.manga,
            source = mangaAndChapters.source,
            isFromSource = isFromSource,
            chapters = mangaAndChapters.chapters.map { item ->
                val downloadState = downloads[item.id]
                val selected = item.id in selectedIds
                if (downloadState == null && !selected) return@map item
                item.copy(
                    downloadState = downloadState ?: item.downloadState,
                    selected = selected,
                )
            },
            availableScanlators = availableScanlators,
            excludedScanlators = excludedScanlators,
            trackingCount = trackingCount,
            hasLoggedInTrackers = hasLoggedInTrackers,
            isRefreshingData = isRefreshingData,
            dialog = dialog,
            hideMissingChapters = hideMissingChapters,
        )
    }
        // Composition would otherwise build these on first read, on the main thread, for every new state
        .onEach {
            it.processedChapters
            it.chapterListItems
            it.isAnySelected
            it.selectedChapters
            it.hasUnreadChapters
            it.hasReadChapters
            it.missingChapterCount
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope + subscriberGate, subscriberGate.whileSubscribed(5.seconds), State.Loading)

    init {
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.statusFlow()
                .filter { it.manga.id == mangaId }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(::updateDownloadState)
        }
        viewModelScope.launch(Dispatchers.Default) {
            downloadManager.progressFlow()
                .filter { it.manga.id == mangaId }
                .catch { logcat(LogPriority.ERROR, it) }
                .collect { downloadProgress[it.chapter.id] = it.progress }
        }

        viewModelScope.launch {
            // The state loads the entry and its chapters anyway, so they aren't queried separately
            val loadedState = state.filterIsInstance<State.Success>().first()
            val manga = loadedState.manga
            val needRefreshInfo = !manga.initialized
            val needRefreshChapter = loadedState.chapters.isEmpty()

            // Fetch info-chapters when needed
            if (needRefreshInfo || needRefreshChapter) {
                isRefreshingData.value = true
                val startTime = Clock.System.now().toEpochMilliseconds()
                try {
                    fetchAllFromSource(
                        manga = manga,
                        manualFetch = false,
                        fetchDetails = needRefreshInfo,
                        fetchChapters = needRefreshChapter,
                    )
                } finally {
                    val elapsedTime = Clock.System.now().toEpochMilliseconds() - startTime
                    if (elapsedTime < 500) {
                        delay(500 - elapsedTime)
                    }
                    isRefreshingData.value = false
                }
            }
        }
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        viewModelScope.launch {
            logcat(LogPriority.INFO) { "[PTR Stage 1] Pull-to-refresh initiated for mangaId=$mangaId (manualFetch=$manualFetch)" }
            isRefreshingData.value = true
            val startTime = Clock.System.now().toEpochMilliseconds()
            try {
                fetchAllFromSource(
                    manga = getMangaAndChapters.awaitManga(mangaId),
                    manualFetch = manualFetch,
                    fetchDetails = true,
                    fetchChapters = true,
                )
            } finally {
                val elapsedTime = Clock.System.now().toEpochMilliseconds() - startTime
                if (elapsedTime < 500) {
                    delay(500 - elapsedTime)
                }
                isRefreshingData.value = false
                logcat(LogPriority.INFO) { "[PTR Stage 10] Pull-to-refresh UI spinner hidden for mangaId=$mangaId" }
            }
        }
    }

    private suspend fun fetchAllFromSource(
        manga: Manga,
        manualFetch: Boolean,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ) {
        try {
            val isFinished = isMangaInFinished(manga)
            logcat(LogPriority.INFO) { "[PTR Stage 2] Evaluating source for '${manga.title}': isLocal=${manga.isLocal()}, isFinished=$isFinished" }
            if (manga.isLocal() || isFinished) {
                syncPhysicalChapters(manga)
            } else {
                logcat(LogPriority.INFO) { "[PTR Stage 2] Fetching remote chapters for online manga '${manga.title}'" }
                val update = updateMangaFromRemote(
                    source = sourceManager.getOrStub(manga.source),
                    manga = manga,
                    fetchDetails = fetchDetails,
                    fetchChapters = fetchChapters,
                    manualFetch = manualFetch,
                )
                    .getOrThrow()

                if (manualFetch) {
                    downloadNewChapters(update.newChapters)
                }
            }
        } catch (_: CancellationException) {
            logcat(LogPriority.INFO) { "[PTR Stage 2] Fetch cancelled for '${manga.title}'" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "[PTR Stage Error] Fetch failed for '${manga.title}'" }
            val message = if (e is NoChaptersException) {
                context.stringResource(MR.strings.no_chapters_error)
            } else {
                with(context) { e.formattedMessage }
            }

            viewModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
        }
    }

    // Manga info - start

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                viewModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_manga),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val manga = state.manga

            if (isFavorited) {
                // Remove from library
                if (updateManga.awaitUpdateFavorite(manga.id, false)) {
                    manga.removeCovers(coverCache)
                    if (manga.isLocal()) {
                        localSourceFileSystem.getMangaDirectory(manga.url)?.delete()
                            ?: localSourceFileSystem.getMangaDirectory(manga.title)?.delete()
                    }
                    onRemoved()
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryManga(manga)

                    if (duplicates.isNotEmpty()) {
                        dialog.value = Dialog.DuplicateManga(manga, duplicates)
                        return@launch
                    }
                }

                // Now check if user previously set categories, when available
                val categories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultCategory.get().toLong()
                val defaultCategory = categories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultCategory != null -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(defaultCategory)
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || categories.isEmpty() -> {
                        val result = updateManga.awaitUpdateFavorite(manga.id, true)
                        if (!result) return@launch
                        moveMangaToCategory(null)
                    }

                    // Choose a category
                    else -> showChangeCategoryDialog()
                }

                // Finally match with enhanced tracking when available
                appScope.launch { addTracks.bindEnhancedTrackers(manga, state.source) }
            }
        }
    }

    fun showRemoveFromLibraryDialog() {
        if (successState?.manga?.favorite == true) dialog.value = Dialog.RemoveFromLibrary
    }

    fun showChangeCategoryDialog() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            val categories = getCategories()
            val selection = getMangaCategoryIds(manga)
            dialog.value = Dialog.ChangeCategory(
                manga = manga,
                initialSelection = categories.mapAsCheckboxState { it.id in selection },
            )
        }
    }

    fun showSetFetchIntervalDialog() {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.SetFetchInterval(manga)
    }

    fun setFetchInterval(manga: Manga, interval: Int) {
        viewModelScope.launch {
            updateManga.awaitUpdateFetchInterval(
                // Custom intervals are negative
                manga.copy(fetchInterval = -interval),
            )
        }
    }

    /**
     * Returns true if the manga has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val manga = successState?.manga ?: return false
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        appScope.launch {
            if (state.manga.isLocal()) {
                localSourceFileSystem.getMangaDirectory(state.manga.url)?.delete()
                    ?: localSourceFileSystem.getMangaDirectory(state.manga.title)?.delete()
            } else {
                downloadManager.deleteManga(state.manga, state.source)
            }
        }
    }

    fun migrateToFinished(context: Context, onSuccess: () -> Unit = {}) {
        val state = successState ?: return
        viewModelScope.launch {
            when (val result = migrateToFinished.await(state.manga)) {
                is MigrateToFinished.Result.Success -> {
                    context.toast("Migrado a Finished correctamente")
                    onSuccess()
                }
                is MigrateToFinished.Result.AlreadyLocal -> {
                    context.toast("El manga ya está en Finished")
                }
                is MigrateToFinished.Result.Error -> {
                    logcat(LogPriority.ERROR, result.error) { "Failed to migrate manga to finished" }
                    context.toast("Error al migrar a Finished")
                }
            }
        }
    }

    fun migrateToLocal(context: Context, onSuccess: () -> Unit = {}) {
        val state = successState ?: return
        viewModelScope.launch {
            when (val result = migrateToLocal.await(state.manga)) {
                is MigrateToLocal.Result.Success -> {
                    context.toast("Migrado a Local correctamente")
                    onSuccess()
                }
                is MigrateToLocal.Result.AlreadyLocal -> {
                    context.toast("El manga ya es Local")
                }
                is MigrateToLocal.Result.Error -> {
                    logcat(LogPriority.ERROR, result.error) { "Failed to migrate manga to local" }
                    context.toast("Error al migrar a Local")
                }
            }
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    private suspend fun getMangaCategoryIds(manga: Manga): List<Long> {
        return getCategories.await(manga.id)
            .map { it.id }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>) {
        moveMangaToCategory(categories)
        if (manga.favorite) return

        viewModelScope.launch {
            updateManga.awaitUpdateFavorite(manga.id, true)
        }
    }

    /**
     * Move the given manga to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveMangaToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveMangaToCategory(categoryIds)
    }

    private fun moveMangaToCategory(categoryIds: List<Long>) {
        viewModelScope.launch {
            setMangaCategories.await(mangaId, categoryIds)
        }
    }

    /**
     * Move the given manga to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveMangaToCategory(category: Category?) {
        moveMangaToCategories(listOfNotNull(category))
    }

    // Manga info - end

    // Chapters list - start

    private fun updateDownloadState(download: Download) {
        val chapterId = download.chapter.id
        downloadStates.update {
            // Terminal states are derived by the queried item itself, so drop the override instead
            // of letting it outlive reality, e.g. showing a since deleted chapter as downloaded.
            if (download.status == Download.State.NOT_DOWNLOADED || download.status == Download.State.DOWNLOADED) {
                it - chapterId
            } else {
                it + (chapterId to download.status)
            }
        }
        if (download.status == Download.State.DOWNLOADING) {
            downloadProgress[chapterId] = download.progress
        } else {
            downloadProgress.remove(chapterId)
        }
    }

    fun getDownloadProgress(chapterId: Long): Int {
        return downloadProgress[chapterId] ?: 0
    }

    private fun List<Chapter>.toChapterListItems(manga: Manga): List<ChapterList.Item> {
        val isLocal = manga.isLocal()
        val queuedDownloads = if (isLocal) emptyMap() else downloadManager.getQueuedDownloadsByChapterId()
        val downloadedChapterIds = if (isLocal) emptySet() else downloadManager.getDownloadedChapterIds(this, manga)
        return map { chapter ->
            val activeDownload = queuedDownloads[chapter.id]
            val downloadedInCurrent = isLocal || chapter.id in downloadedChapterIds
            val downloadedInOther = if (isLocal || downloadedInCurrent) false else downloadManager.isChapterDownloadedInAnyOtherSource(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source)
            val isFromDifferent = downloadedInOther || chapter.url.contains("#migrated_")

            val downloaded = downloadedInCurrent || downloadedInOther || isFromDifferent
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> Download.State.DOWNLOADED
                else -> Download.State.NOT_DOWNLOADED
            }

            ChapterList.Item(
                chapter = chapter,
                downloadState = downloadState,
                fromDifferentSource = isFromDifferent,
            )
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    fun chapterSwipe(chapterItem: ChapterList.Item, swipeAction: LibraryPreferences.ChapterSwipeAction) {
        viewModelScope.launch {
            executeChapterSwipeAction(chapterItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.ChapterSwipeAction.Disabled]
     */
    private fun executeChapterSwipeAction(
        chapterItem: ChapterList.Item,
        swipeAction: LibraryPreferences.ChapterSwipeAction,
    ) {
        val chapter = chapterItem.chapter
        when (swipeAction) {
            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                markChaptersRead(listOf(chapter), !chapter.read)
            }
            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                bookmarkChapters(listOf(chapter), !chapter.bookmark)
            }
            LibraryPreferences.ChapterSwipeAction.Download -> {
                val downloadAction: ChapterDownloadAction = when (chapterItem.downloadState) {
                    Download.State.ERROR,
                    Download.State.NOT_DOWNLOADED,
                    -> ChapterDownloadAction.START_NOW
                    Download.State.QUEUE,
                    Download.State.DOWNLOADING,
                    -> ChapterDownloadAction.CANCEL
                    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                }
                runChapterDownloadActions(
                    items = listOf(chapterItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.ChapterSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): Chapter? {
        val successState = successState ?: return null
        return successState.chapters.getNextUnread(successState.manga)
    }

    private fun getUnreadChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> !chapter.read && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getUnreadChaptersSorted(): List<Chapter> {
        val manga = successState?.manga ?: return emptyList()
        val chaptersSorted = getUnreadChapters().sortedWith(getChapterSort(manga))
        return if (manga.sortDescending()) chaptersSorted.reversed() else chaptersSorted
    }

    private fun getBookmarkedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (chapter, dlStatus) -> chapter.bookmark && dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun getNotDownloadedChapters(): List<Chapter> {
        val chapterItems = if (skipFiltered) filteredChapters.orEmpty() else allChapters.orEmpty()
        return chapterItems
            .filter { (_, dlStatus) -> dlStatus == Download.State.NOT_DOWNLOADED }
            .map { it.chapter }
    }

    private fun startDownload(
        chapters: List<Chapter>,
        startNow: Boolean,
    ) {
        if (successState == null) return
        val startNowId = if (startNow) chapters.singleOrNull()?.id ?: return else null

        viewModelScope.launch {
            // The queueing outlives the screen, the add to library prompt doesn't
            appScope.launch {
                if (startNowId != null) {
                    downloadManager.startDownloadNow(startNowId)
                } else {
                    downloadChapters(chapters)
                }
            }.join()
            if (startNowId == null) toggleAllSelection(false)

            if (!isFavorited && !hasPromptedToAddBefore) {
                hasPromptedToAddBefore = true
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runChapterDownloadActions(
        items: List<ChapterList.Item>,
        action: ChapterDownloadAction,
    ) {
        when (action) {
            ChapterDownloadAction.START -> {
                startDownload(items.map { it.chapter }, false)
                if (items.any { it.downloadState == Download.State.ERROR }) {
                    downloadManager.startDownloads()
                }
            }
            ChapterDownloadAction.START_NOW -> {
                val chapter = items.singleOrNull()?.chapter ?: return
                startDownload(listOf(chapter), true)
            }
            ChapterDownloadAction.CANCEL -> {
                val chapterId = items.singleOrNull()?.id ?: return
                cancelDownload(chapterId)
            }
            ChapterDownloadAction.DELETE -> {
                deleteChapters(items.map { it.chapter })
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val chaptersToDownload = when (action) {
            DownloadAction.NEXT_1_CHAPTER -> getUnreadChaptersSorted().take(1)
            DownloadAction.NEXT_5_CHAPTERS -> getUnreadChaptersSorted().take(5)
            DownloadAction.NEXT_10_CHAPTERS -> getUnreadChaptersSorted().take(10)
            DownloadAction.NEXT_25_CHAPTERS -> getUnreadChaptersSorted().take(25)
            DownloadAction.UNREAD_CHAPTERS -> getUnreadChapters()
            DownloadAction.BOOKMARKED_CHAPTERS -> getBookmarkedChapters()
            DownloadAction.NOT_DOWNLOADED -> getNotDownloadedChapters()
        }
        if (chaptersToDownload.isNotEmpty()) {
            startDownload(chaptersToDownload, false)
        }
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        viewModelScope.launch {
            downloadManager.cancelQueuedDownloads(listOf(activeDownload))
            updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
        }
    }

    fun markPreviousChapterRead(pointer: Chapter) {
        val manga = successState?.manga ?: return
        val chapters = filteredChapters.orEmpty().map { it.chapter }
        val prevChapters = if (manga.sortDescending()) chapters.asReversed() else chapters
        val pointerPos = prevChapters.indexOf(pointer)
        if (pointerPos != -1) markChaptersRead(prevChapters.take(pointerPos), true)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param chapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(chapters: List<Chapter>, read: Boolean) {
        toggleAllSelection(false)
        if (chapters.isEmpty()) return
        viewModelScope.launch {
            // The read state is written even if the screen closes, the tracking prompt isn't
            appScope.launch {
                setReadStatus.await(
                    read = read,
                    chapters = chapters.toTypedArray(),
                )
            }.join()

            if (!read || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launch
            }

            refreshTrackers()

            val tracks = getTracks.await(mangaId)
            val maxChapterNumber = chapters.maxOf { it.chapterNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxChapterNumber > track.lastChapterRead }

            if (!shouldPromptTrackingUpdate) return@launch
            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackChapter.await(context, mangaId, maxChapterNumber)
                context.toast(context.stringResource(MR.strings.trackers_updated_summary, maxChapterNumber.toInt()))
                return@launch
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update, maxChapterNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackChapter.await(context, mangaId, maxChapterNumber)
            }
        }
    }

    private suspend fun refreshTrackers() {
        refreshTracks.await(mangaId)
            .filter { it.first != null }
            .forEach { (track, e) ->
                logcat(LogPriority.ERROR, e) {
                    "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                }
                withContext(Dispatchers.Main) {
                    context.toast(
                        context.stringResource(
                            MR.strings.track_error,
                            track!!.name,
                            e.message ?: "",
                        ),
                    )
                }
            }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    private suspend fun downloadChapters(chapters: List<Chapter>) {
        val manga = successState?.manga ?: return
        downloadManager.downloadChapters(manga, chapters)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param chapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(chapters: List<Chapter>, bookmarked: Boolean) {
        viewModelScope.launch {
            chapters
                .filterNot { it.bookmark == bookmarked }
                .map { ChapterUpdate(it.id) { bookmark = bookmarked } }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<Chapter>) {
        val state = successState ?: return
        logcat(LogPriority.INFO) { "MangaViewModel.deleteChapters initiated for ${chapters.size} chapters in '${state.manga.title}' (isLocal=${state.manga.isLocal()})" }
        // Outlives the screen, which the delete dialog usually closes along with
        appScope.launch {
            try {
                chapters.forEach { ch ->
                    logcat(LogPriority.INFO) { "Deleting chapter target: id=${ch.id}, name='${ch.name}', url='${ch.url}', isBookmarked=${ch.bookmark}, isRead=${ch.read}" }
                }

                downloadManager.deleteChapters(chapters, state.manga, state.source)

                if (state.manga.isLocal() || isMangaInFinished(state.manga)) {
                    logcat(LogPriority.INFO) { "Deleting DB entries for ${chapters.size} chapters in local/finished manga '${state.manga.title}'" }
                    chapterRepository.updateFromRemote(
                        removedIds = chapters.map { it.id },
                        added = emptyList(),
                        updated = emptyList(),
                    )
                    val downloadMangaDir = downloadProvider.findMangaDir(state.manga.title, state.source)
                    if (downloadMangaDir != null && downloadMangaDir.exists()) {
                        downloadManager.refreshMangaCache(state.manga, downloadMangaDir)
                    } else {
                        downloadManager.invalidateCache()
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete chapters for ${state.manga.title}" }
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        appScope.launch {
            val manga = successState?.manga ?: return@launch
            val chaptersToDownload = filterChaptersForDownload.await(manga, chapters)

            if (chaptersToDownload.isNotEmpty()) {
                downloadChapters(chaptersToDownload)
            }
        }
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_UNREAD
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_READ
        }
        appScope.launch {
            setMangaChapterFlags.awaitSetUnreadFilter(manga, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetDownloadedFilter(manga, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: TriState) {
        val manga = successState?.manga ?: return

        val flag = when (state) {
            TriState.DISABLED -> Manga.SHOW_ALL
            TriState.ENABLED_IS -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        appScope.launch {
            setMangaChapterFlags.awaitSetBookmarkFilter(manga, flag)
        }
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetDisplayMode(manga, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val manga = successState?.manga ?: return

        appScope.launch {
            setMangaChapterFlags.awaitSetSortingModeOrFlipOrder(manga, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            appScope.launch {
                libraryPreferences.setChapterSettingsDefault(manga)
                if (applyToExisting) {
                    setMangaDefaultChapterFlags.awaitAll()
                }
            }.join()
            snackbarHostState.showSnackbar(message = context.stringResource(MR.strings.chapter_settings_updated))
        }
    }

    fun resetToDefaultSettings() {
        val manga = successState?.manga ?: return
        viewModelScope.launch {
            setMangaDefaultChapterFlags.await(manga)
        }
    }

    fun toggleSelection(
        item: ChapterList.Item,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        val chapters = successState?.processedChapters ?: return
        val selectedIndex = chapters.indexOfFirst { it.id == item.chapter.id }
        if (selectedIndex < 0) return

        // Read from the ids, as the state catches up with a selection only after it's been made
        val selectedIds = HashSet(selectedChapterIds.value)
        if ((item.id in selectedIds) == selected) return

        val firstSelection = chapters.none { it.id in selectedIds }
        selectedIds.addOrRemove(item.id, selected)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = selectedIndex
                selectedPositions[1] = selectedIndex
            } else {
                // Try to select the items in-between when possible
                val range: IntRange
                if (selectedIndex < selectedPositions[0]) {
                    range = selectedIndex + 1..<selectedPositions[0]
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    range = (selectedPositions[1] + 1)..<selectedIndex
                    selectedPositions[1] = selectedIndex
                } else {
                    // Just select itself
                    range = IntRange.EMPTY
                }

                range.forEach { selectedIds.add(chapters[it].id) }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (selectedIndex == selectedPositions[0]) {
                    selectedPositions[0] = chapters.indexOfFirst { it.id in selectedIds }
                } else if (selectedIndex == selectedPositions[1]) {
                    selectedPositions[1] = chapters.indexOfLast { it.id in selectedIds }
                }
            } else {
                if (selectedIndex < selectedPositions[0]) {
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    selectedPositions[1] = selectedIndex
                }
            }
        }
        selectedChapterIds.value = selectedIds
    }

    fun toggleAllSelection(selected: Boolean) {
        selectedChapterIds.value = if (selected) allChapters.orEmpty().mapTo(HashSet()) { it.id } else emptySet()
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        val selectedIds = selectedChapterIds.value
        selectedChapterIds.value = allChapters.orEmpty().filterNot { it.id in selectedIds }.mapTo(HashSet()) { it.id }
        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    // Chapters list - end

    sealed interface Dialog {
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteChapters(val chapters: List<Chapter>) : Dialog
        data class DuplicateManga(val manga: Manga, val duplicates: List<MangaWithChapterCount>) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
        data class SetFetchInterval(val manga: Manga) : Dialog
        data object RemoveFromLibrary : Dialog
        data object SettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullCover : Dialog
    }

    fun dismissDialog() {
        dialog.value = null
    }

    fun showDeleteChapterDialog(chapters: List<Chapter>) {
        dialog.value = Dialog.DeleteChapters(chapters)
    }

    fun showSettingsDialog() {
        dialog.value = Dialog.SettingsSheet
    }

    fun showTrackDialog() {
        dialog.value = Dialog.TrackSheet
    }

    fun showCoverDialog() {
        dialog.value = Dialog.FullCover
    }

    fun showMigrateDialog(duplicate: Manga) {
        val manga = successState?.manga ?: return
        dialog.value = Dialog.Migrate(target = manga, current = duplicate)
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        viewModelScope.launch {
            setExcludedScanlators.await(mangaId, excludedScanlators)
        }
    }

    private data class MangaAndChapters(val manga: Manga, val source: Source, val chapters: List<ChapterList.Item>)

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val manga: Manga,
            val source: Source,
            val isFromSource: Boolean,
            val chapters: List<ChapterList.Item>,
            val availableScanlators: Set<String>,
            val excludedScanlators: Set<String>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val hideMissingChapters: Boolean = false,
        ) : State {
            val processedChapters by lazy {
                chapters.applyFilters(manga).toList()
            }

            val isAnySelected by lazy {
                chapters.fastAny { it.selected }
            }

            val selectedChapters by lazy {
                processedChapters.fastFilter { it.selected }
            }

            val hasUnreadChapters by lazy {
                processedChapters.fastAny { !it.chapter.read }
            }

            val hasReadChapters by lazy {
                chapters.fastAny { it.chapter.read }
            }

            val missingChapterCount by lazy {
                if (hideMissingChapters) return@lazy 0
                processedChapters.fastMap { it.chapter.chapterNumber }.missingChaptersCount()
            }

            val chapterListItems by lazy {
                if (hideMissingChapters) {
                    return@lazy processedChapters
                }

                processedChapters.insertSeparators { before, after ->
                    val (lowerChapter, higherChapter) = if (manga.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherChapter == null) return@insertSeparators null

                    if (lowerChapter == null) {
                        floor(higherChapter.chapter.chapterNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherChapter.chapter, lowerChapter.chapter)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            ChapterList.MissingCount(
                                id = "${lowerChapter?.id}-${higherChapter.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val filterActive: Boolean
                get() = scanlatorFilterActive || manga.chaptersFiltered()

            /**
             * Applies the view filters to the list of chapters obtained from the database.
             * @return an observable of the list of chapters filtered and sorted.
             */
            private fun List<ChapterList.Item>.applyFilters(manga: Manga): Sequence<ChapterList.Item> {
                val isLocalManga = manga.isLocal()
                val unreadFilter = manga.unreadFilter
                val downloadedFilter = manga.downloadedFilter
                val bookmarkedFilter = manga.bookmarkedFilter
                val chapterSort = getChapterSort(manga)
                return asSequence()
                    .filter { (chapter) -> applyFilter(unreadFilter) { !chapter.read } }
                    .filter { (chapter) -> applyFilter(bookmarkedFilter) { chapter.bookmark } }
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalManga } }
                    .sortedWith { (chapter1), (chapter2) -> chapterSort(chapter1, chapter2) }
            }
        }
    }

    private suspend fun isMangaInFinished(manga: Manga): Boolean {
        val categories = getCategories.await(manga.id)
        if (categories.any { it.name.equals("Finished", ignoreCase = true) }) {
            return true
        }
        val finishedDir = storageManager.getFinishedSourceDirectory() ?: return false
        val mangaDirName = DiskUtil.buildValidFilename(manga.title)
        return finishedDir.findFile(manga.title) != null || finishedDir.findFile(mangaDirName) != null
    }

    private suspend fun syncPhysicalChapters(manga: Manga) {
        logcat(LogPriority.INFO) { "[PTR Stage 3] Locating physical directory for '${manga.title}'" }
        val source = sourceManager.getOrStub(manga.source)
        val downloadMangaDir = downloadProvider.findMangaDir(manga.title, source)
            ?: storageManager.getFinishedSourceDirectory()?.findFile(manga.title)
            ?: storageManager.getFinishedSourceDirectory()?.findFile(DiskUtil.buildValidFilename(manga.title))
            ?: storageManager.getFinishedSourceDirectory()?.listFiles()?.firstOrNull {
                it.isDirectory && (it.name.equals(manga.title, ignoreCase = true) || it.name.equals(DiskUtil.buildValidFilename(manga.title), ignoreCase = true))
            }
            ?: storageManager.getLocalSourceDirectory()?.findFile(manga.title)
            ?: storageManager.getLocalSourceDirectory()?.findFile(DiskUtil.buildValidFilename(manga.title))
            ?: storageManager.getLocalSourceDirectory()?.findFile(manga.url)
            ?: localSourceFileSystem.getMangaDirectory(manga.url)
        if (downloadMangaDir == null) {
            logcat(LogPriority.WARN) { "[PTR Stage 3] Physical directory NOT found for '${manga.title}'" }
            return
        }

        if (!downloadMangaDir.exists() || !downloadMangaDir.isDirectory) {
            logcat(LogPriority.WARN) { "[PTR Stage 3] Directory does not exist or is not folder: ${downloadMangaDir.uri}" }
            return
        }

        logcat(LogPriority.INFO) { "[PTR Stage 3] Located physical folder: ${downloadMangaDir.uri}" }

        val physicalFiles = downloadMangaDir.listFiles().orEmpty()
            .filter { !it.name.orEmpty().startsWith('.') && it.name != "cover.jpg" }
            .filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) }

        logcat(LogPriority.INFO) { "[PTR Stage 4] Found ${physicalFiles.size} physical chapter files on disk in '${manga.title}'" }

        val sortedPhysicalFiles = physicalFiles.sortedWith { f1, f2 ->
            val name1 = f1.nameWithoutExtension.orEmpty()
            val name2 = f2.nameWithoutExtension.orEmpty()
            val num1 = ChapterRecognition.parseChapterNumber(manga.title, name1, -1.0)
            val num2 = ChapterRecognition.parseChapterNumber(manga.title, name2, -1.0)
            val vol1 = ChapterRecognition.parseVolumeNumber(manga.title, name1)
            val vol2 = ChapterRecognition.parseVolumeNumber(manga.title, name2)

            if (num1 >= 0 && num2 >= 0 && num1 != num2) {
                if (vol1 >= 0 && vol2 >= 0 && vol1 != vol2) {
                    vol1.compareTo(vol2)
                } else {
                    num1.compareTo(num2)
                }
            } else {
                val ver1 = ChapterRecognition.parseVersionNumber(name1)
                val ver2 = ChapterRecognition.parseVersionNumber(name2)
                if (ver1 != ver2) {
                    ver1.compareTo(ver2)
                } else {
                    name1.compareToWithCollator(name2)
                }
            }
        }

        val dbChapters = chapterRepository.getChapterByMangaId(manga.id)

        val matchedDbChapterIds = mutableSetOf<Long>()
        val newChaptersToAdd = mutableListOf<Chapter>()
        val pendingUrlUpdates = mutableListOf<Pair<Long, String>>()
        val remoteUpdates = mutableListOf<ChapterRemoteUpdate>()

        sortedPhysicalFiles.forEachIndexed { index, file ->
            val fileNameNoExt = file.nameWithoutExtension.orEmpty()
            val fileNameRaw = file.name.orEmpty()
            val fileNum = ChapterRecognition.parseChapterNumber(manga.title, fileNameNoExt, -1.0)
            val fileUrl = "${manga.url}/$fileNameRaw"
            val calculatedOrder = index.toLong()

            var matchedChapter = dbChapters.find { chapter ->
                chapter.id !in matchedDbChapterIds &&
                    (chapter.url == fileUrl || chapter.url.contains(fileNameRaw) || chapter.url.contains(fileNameNoExt))
            }
            if (matchedChapter == null) {
                matchedChapter = dbChapters.find { chapter ->
                    chapter.id !in matchedDbChapterIds &&
                        (fileNameNoExt.equals(chapter.name, ignoreCase = true) || fileNameNoExt.equals(DiskUtil.buildValidFilename(chapter.name), ignoreCase = true))
                }
            }
            if (matchedChapter == null && fileNum >= 0) {
                matchedChapter = dbChapters.find { chapter ->
                    chapter.id !in matchedDbChapterIds && chapter.chapterNumber >= 0 && chapter.chapterNumber == fileNum
                }
            }

            if (matchedChapter != null) {
                matchedDbChapterIds.add(matchedChapter.id)
                logcat(LogPriority.INFO) { "[PTR Stage 5] Matched physical file '${fileNameRaw}' -> DB chapter id=${matchedChapter.id}, name='${matchedChapter.name}'" }
                if (matchedChapter.url != fileUrl && (manga.isLocal() || isMangaInFinished(manga) || matchedChapter.url.contains(fileNameNoExt))) {
                    pendingUrlUpdates.add(matchedChapter.id to fileUrl)
                }
                if (matchedChapter.name != fileNameNoExt || matchedChapter.sourceOrder != calculatedOrder) {
                    logcat(LogPriority.INFO) { "[PTR Stage 5] Updating DB name/order for id=${matchedChapter.id} from '${matchedChapter.name}' -> '${fileNameNoExt}' (order=$calculatedOrder)" }
                    remoteUpdates.add(
                        ChapterRemoteUpdate(
                            id = matchedChapter.id,
                            name = fileNameNoExt,
                            scanlator = matchedChapter.scanlator,
                            chapterNumber = if (fileNum >= 0) fileNum else matchedChapter.chapterNumber,
                            dateUpload = matchedChapter.dateUpload,
                            sourceOrder = calculatedOrder,
                            memo = matchedChapter.memo,
                        ),
                    )
                }
            } else {
                logcat(LogPriority.INFO) { "[PTR Stage 5] New physical file '${fileNameRaw}' not in DB. Preparing to add as new chapter (num=$fileNum, order=$calculatedOrder)" }
                val oldMatch = dbChapters.find { it.chapterNumber >= 0 && it.chapterNumber == fileNum }
                val newChapter = Chapter.create().copy(
                    mangaId = manga.id,
                    url = fileUrl,
                    name = fileNameNoExt,
                    chapterNumber = if (fileNum >= 0) fileNum else -1.0,
                    sourceOrder = calculatedOrder,
                    read = oldMatch?.read ?: false,
                    bookmark = oldMatch?.bookmark ?: false,
                    lastPageRead = oldMatch?.lastPageRead ?: 0,
                    dateFetch = oldMatch?.dateFetch ?: Clock.System.now(),
                )
                newChaptersToAdd.add(newChapter)
            }
        }

        val dbIdsToDelete = dbChapters.map { it.id }.filter { it !in matchedDbChapterIds }
        val chapterUpdates = mutableListOf<ChapterUpdate>()

        val deletedChapters = dbChapters.filter { it.id in dbIdsToDelete }
        deletedChapters.forEach { deletedChapter ->
            logcat(LogPriority.INFO) { "[PTR Stage 6] Marking stale/duplicate DB chapter id=${deletedChapter.id} ('${deletedChapter.name}') for removal" }
            if (deletedChapter.read || deletedChapter.bookmark || deletedChapter.lastPageRead > 0) {
                val matchedPartner = dbChapters.find { it.id in matchedDbChapterIds && it.chapterNumber >= 0 && it.chapterNumber == deletedChapter.chapterNumber }
                if (matchedPartner != null && (!matchedPartner.read || !matchedPartner.bookmark || matchedPartner.lastPageRead < deletedChapter.lastPageRead)) {
                    logcat(LogPriority.INFO) { "[PTR Stage 6] Transferring read/bookmark status from deleted id=${deletedChapter.id} to matched id=${matchedPartner.id}" }
                    chapterUpdates.add(
                        ChapterUpdate(matchedPartner.id) {
                            if (deletedChapter.read) read = true
                            if (deletedChapter.bookmark) bookmark = true
                            if (deletedChapter.lastPageRead > matchedPartner.lastPageRead) lastPageRead = deletedChapter.lastPageRead
                        }
                    )
                }
            }
        }

        if (chapterUpdates.isNotEmpty()) {
            logcat(LogPriority.INFO) { "[PTR Stage 7] Applying ${chapterUpdates.size} chapter status updates in DB" }
            updateChapter.awaitAll(chapterUpdates)
        }

        val existingDbUrls = dbChapters.map { it.url }.toSet()
        val finalNewChaptersToAdd = newChaptersToAdd
            .distinctBy { it.url }
            .filter { it.url !in existingDbUrls }

        logcat(LogPriority.INFO) { "[PTR Stage 8] Database transaction: removing ${dbIdsToDelete.size} IDs, adding ${finalNewChaptersToAdd.size} new, updating ${remoteUpdates.size} remote" }

        if (finalNewChaptersToAdd.isNotEmpty() || dbIdsToDelete.isNotEmpty() || remoteUpdates.isNotEmpty()) {
            chapterRepository.updateFromRemote(
                removedIds = dbIdsToDelete,
                added = finalNewChaptersToAdd,
                updated = remoteUpdates,
            )
        }

        pendingUrlUpdates.forEach { (chapterId, newUrl) ->
            logcat(LogPriority.INFO) { "[PTR Stage 8] Updating remoteUrl in DB for id=$chapterId -> '$newUrl'" }
            runCatching { chapterRepository.updateUrl(chapterId, newUrl) }
        }

        val physicalFileNamesNoExt = physicalFiles.map {
            if (it.isDirectory) it.name.orEmpty() else it.nameWithoutExtension.orEmpty()
        }.toSet()

        logcat(LogPriority.INFO) { "[PTR Stage 9] Refreshing download cache for manga '${manga.title}' with ${physicalFileNamesNoExt.size} chapters" }
        downloadManager.setMangaChapterDirs(manga, physicalFileNamesNoExt)
        logcat(LogPriority.INFO) { "[PTR Stage 10] Physical chapter sync complete for '${manga.title}'" }
    }



    fun sanitizeChapterNames(context: Context) {
        val successState = state.value as? State.Success ?: return
        val manga = successState.manga
        val notificationId = (manga.id xor 0x503).toInt()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                logcat(LogPriority.INFO) { "[Sanitize Stage 1] Starting name sanitization for manga '${manga.title}' (id=${manga.id})" }
                context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                    setContentTitle("Sanitizando capítulos: ${manga.title}")
                    setContentText("Iniciando sanitización...")
                    setSmallIcon(R.drawable.ic_mihon)
                    setProgress(0, 0, true)
                    setOngoing(true)
                    setOnlyAlertOnce(true)
                }

                val source = sourceManager.getOrStub(manga.source)
                val downloadMangaDir = downloadProvider.findMangaDir(manga.title, source)
                    ?: storageManager.getFinishedSourceDirectory()?.findFile(manga.title)
                    ?: storageManager.getFinishedSourceDirectory()?.findFile(DiskUtil.buildValidFilename(manga.title))
                    ?: storageManager.getFinishedSourceDirectory()?.listFiles()?.firstOrNull {
                        it.isDirectory && (it.name.equals(manga.title, ignoreCase = true) || it.name.equals(DiskUtil.buildValidFilename(manga.title), ignoreCase = true))
                    }
                    ?: storageManager.getLocalSourceDirectory()?.findFile(manga.title)
                    ?: storageManager.getLocalSourceDirectory()?.findFile(DiskUtil.buildValidFilename(manga.title))
                    ?: storageManager.getLocalSourceDirectory()?.findFile(manga.url)
                    ?: localSourceFileSystem.getMangaDirectory(manga.url)
                    ?: downloadProvider.findSourceDir(source)?.findFile(DiskUtil.buildValidFilename(manga.title))
                    ?: downloadProvider.findSourceDir(source)?.listFiles()?.firstOrNull {
                        it.isDirectory && (it.name.equals(manga.title, ignoreCase = true) || it.name.equals(DiskUtil.buildValidFilename(manga.title), ignoreCase = true))
                    }
                val chapters = chapterRepository.getChapterByMangaId(manga.id)
                logcat(LogPriority.INFO) { "[Sanitize Stage 2] Located manga directory: ${downloadMangaDir?.uri}" }

                val remoteUpdates = mutableListOf<ChapterRemoteUpdate>()
                val removeIds = mutableListOf<Long>()
                val pendingUrlUpdates = mutableListOf<Pair<Long, String>>()

                if (downloadMangaDir != null && downloadMangaDir.exists()) {
                    val downloadedFiles = downloadMangaDir.listFiles().orEmpty()
                        .filter { !it.name.orEmpty().startsWith('.') && it.name != "cover.jpg" }

                    val totalFiles = downloadedFiles.size
                    logcat(LogPriority.INFO) { "[Sanitize Stage 3] Found $totalFiles physical files in '${manga.title}'" }
                    val usedNames = mutableSetOf<String>()

                    downloadedFiles.forEachIndexed { index, file ->
                        val currentProgress = index + 1
                        context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                            setContentTitle("Sanitizando capítulos: ${manga.title}")
                            setContentText("$currentProgress / $totalFiles capítulos")
                            setSmallIcon(R.drawable.ic_mihon)
                            setProgress(totalFiles, currentProgress, false)
                            setOngoing(true)
                            setOnlyAlertOnce(true)
                        }

                        val fileNameNoExt = file.nameWithoutExtension.orEmpty()
                        val fileNameRaw = file.name.orEmpty()

                        var matchedChapter = chapters.find { chapter ->
                            chapter.id !in removeIds && remoteUpdates.none { it.id == chapter.id } &&
                                downloadProvider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                                    .any { it.equals(fileNameNoExt, ignoreCase = true) || it.equals(fileNameRaw, ignoreCase = true) }
                        }
                        if (matchedChapter == null) {
                            matchedChapter = chapters.find { chapter ->
                                chapter.id !in removeIds && remoteUpdates.none { it.id == chapter.id } &&
                                    (fileNameNoExt.equals(chapter.name, ignoreCase = true) || fileNameNoExt.equals(DiskUtil.buildValidFilename(chapter.name), ignoreCase = true))
                            }
                        }
                        if (matchedChapter == null) {
                            val parsedNum = ChapterRecognition.parseChapterNumber(manga.title, fileNameNoExt, -1.0)
                            if (parsedNum >= 0) {
                                matchedChapter = chapters.find { chapter ->
                                    chapter.id !in removeIds && remoteUpdates.none { it.id == chapter.id } && chapter.chapterNumber > 0 && chapter.chapterNumber == parsedNum
                                }
                            }
                        }

                        val chNum = matchedChapter?.chapterNumber?.takeIf { it >= 0 }
                            ?: ChapterRecognition.parseChapterNumber(manga.title, fileNameNoExt, -1.0)

                        val paddedStr = formatPaddedChapterNumber(chNum, fileNameNoExt)
                        val isArchive = file.name.orEmpty().endsWith(".cbz", ignoreCase = true) || file.name.orEmpty().endsWith(".zip", ignoreCase = true)
                        val ext = if (file.isFile && isArchive) "." + file.extension else ""

                        val sanitizedName = "Chapter $paddedStr"
                        val candidateName = "$sanitizedName$ext"

                        var targetName = candidateName
                        var version = 2
                        while (usedNames.contains(targetName) || (downloadMangaDir.findFile(targetName) != null && targetName != fileNameRaw)) {
                            targetName = "$sanitizedName.v$version$ext"
                            version++
                        }
                        usedNames.add(targetName)

                        if (targetName != fileNameRaw) {
                            logcat(LogPriority.INFO) { "[Sanitize Stage 4] Renaming file physically on disk: '$fileNameRaw' -> '$targetName'" }
                            moveUniFile(file, downloadMangaDir, targetName)
                        }

                        val newDbName = targetName.removeSuffix(ext)
                        if (matchedChapter != null) {
                            if (manga.isLocal() || matchedChapter.url.contains(fileNameNoExt) || matchedChapter.url.contains(fileNameRaw)) {
                                val newUrl = matchedChapter.url
                                    .replace(fileNameRaw, targetName)
                                    .replace(fileNameNoExt, newDbName)
                                if (newUrl != matchedChapter.url) {
                                    pendingUrlUpdates.add(matchedChapter.id to newUrl)
                                }
                            }

                            remoteUpdates.add(
                                ChapterRemoteUpdate(
                                    id = matchedChapter.id,
                                    name = newDbName,
                                    scanlator = matchedChapter.scanlator,
                                    chapterNumber = matchedChapter.chapterNumber,
                                    dateUpload = matchedChapter.dateUpload,
                                    sourceOrder = matchedChapter.sourceOrder,
                                    memo = matchedChapter.memo,
                                ),
                            )
                        }
                    }
                }

                val usedDbNames = remoteUpdates.map { it.name }.toMutableSet()
                logcat(LogPriority.INFO) { "[Sanitize Stage 5] Formatting and deduplicating DB chapters (total DB chapters=${chapters.size})" }

                chapters.forEach { chapter ->
                    if (chapter.id in removeIds || remoteUpdates.any { it.id == chapter.id }) return@forEach

                    val sameNumChapters = chapters.filter { it.chapterNumber >= 0 && it.chapterNumber == chapter.chapterNumber }
                    val isDownloaded = downloadManager.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source) ||
                        downloadManager.isChapterDownloadedInAnyOtherSource(chapter.name, chapter.scanlator, chapter.url, manga.title, manga.source)

                    if (sameNumChapters.size > 1 && !isDownloaded && !chapter.read && chapter.lastPageRead == 0 && !chapter.bookmark) {
                        val hasHandledPartner = remoteUpdates.any { update ->
                            val updatedCh = chapters.find { it.id == update.id }
                            updatedCh != null && updatedCh.chapterNumber == chapter.chapterNumber
                        }
                        if (hasHandledPartner) {
                            removeIds.add(chapter.id)
                            return@forEach
                        }
                    }

                    if (chapter.chapterNumber >= 0) {
                        val paddedStr = formatPaddedChapterNumber(chapter.chapterNumber, chapter.name)
                        val baseDbName = "Chapter $paddedStr"

                        var targetDbName = baseDbName
                        var version = 2
                        while (usedDbNames.contains(targetDbName)) {
                            targetDbName = "$baseDbName.v$version"
                            version++
                        }
                        usedDbNames.add(targetDbName)

                        if (chapter.name != targetDbName) {
                            remoteUpdates.add(
                                ChapterRemoteUpdate(
                                    id = chapter.id,
                                    name = targetDbName,
                                    scanlator = chapter.scanlator,
                                    chapterNumber = chapter.chapterNumber,
                                    dateUpload = chapter.dateUpload,
                                    sourceOrder = chapter.sourceOrder,
                                    memo = chapter.memo,
                                ),
                            )
                        }
                    }
                }

                logcat(LogPriority.INFO) { "[Sanitize Stage 6] Applying DB updates: removing ${removeIds.size} IDs, updating ${remoteUpdates.size} chapter names" }

                if (remoteUpdates.isNotEmpty() || removeIds.isNotEmpty()) {
                    chapterRepository.updateFromRemote(removeIds, emptyList(), remoteUpdates)
                }

                logcat(LogPriority.INFO) { "[Sanitize Stage 7] Updating ${pendingUrlUpdates.size} chapter URLs in DB" }

                pendingUrlUpdates.forEach { (chapterId, newUrl) ->
                    runCatching { chapterRepository.updateUrl(chapterId, newUrl) }
                }

                logcat(LogPriority.INFO) { "[Sanitize Stage 8] Refreshing download cache for manga '${manga.title}'" }

                if (downloadMangaDir != null && downloadMangaDir.exists()) {
                    downloadManager.refreshMangaCache(manga, downloadMangaDir)
                } else {
                    downloadManager.invalidateCache()
                }

                logcat(LogPriority.INFO) { "[Sanitize Stage 9] Sanitization completed successfully for '${manga.title}'" }

                context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                    setContentTitle("Sanitización completada: ${manga.title}")
                    setContentText("Se sanitizaron los nombres de los capítulos")
                    setSmallIcon(R.drawable.ic_mihon)
                    setProgress(0, 0, false)
                    setOngoing(false)
                    setAutoCancel(true)
                }

                withContext(Dispatchers.Main) {
                    context.toast("Nombres sanitizados correctamente")
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to sanitize chapter names for ${manga.title}" }
                context.notify(notificationId, Notifications.CHANNEL_COMMON) {
                    setContentTitle("Error al sanitizar capítulos")
                    setContentText(manga.title)
                    setSmallIcon(R.drawable.ic_mihon)
                    setProgress(0, 0, false)
                    setOngoing(false)
                    setAutoCancel(true)
                }
            }
        }
    }

    private fun formatPaddedChapterNumber(number: Double, fallbackText: String): String {
        val num = if (number >= 0) number else {
            val match = Regex("""\d+(?:\.\d+)?""").find(fallbackText)
            match?.value?.toDoubleOrNull() ?: 1.0
        }

        val intPart = num.toInt()
        val decPart = num - intPart
        val paddedInt = intPart.toString().padStart(3, '0')

        return if (decPart > 0.0001) {
            val decStr = num.toString().substringAfter('.')
            "$paddedInt.$decStr"
        } else {
            paddedInt
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
            logcat(LogPriority.ERROR, e) { "Failed to move file ${sourceFile.name} to $newName in ${targetDir.name}" }
        }
    }
}

@Immutable
sealed class ChapterList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : ChapterList()

    @Immutable
    data class Item(
        val chapter: Chapter,
        val downloadState: Download.State,
        val selected: Boolean = false,
        val fromDifferentSource: Boolean = false,
    ) : ChapterList() {
        val id = chapter.id
        val isDownloaded = downloadState == Download.State.DOWNLOADED
    }
}
