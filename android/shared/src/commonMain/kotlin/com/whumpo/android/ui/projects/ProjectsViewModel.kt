package com.whumpo.android.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whumpo.android.ui.common.StatusChip
import com.whumpo.android.ui.common.TimeLabel
import com.whumpo.android.ui.common.Tone
import com.whumpo.android.ui.common.loadUi
import com.whumpo.android.ui.common.timeLabel
import com.whumpo.android.ui.common.userMessage
import com.whumpo.android.ui.photos.NoUploadStore
import com.whumpo.android.ui.photos.PendingUpload
import com.whumpo.android.ui.photos.PhotoPreparer
import com.whumpo.android.ui.photos.UploadStore
import com.whumpo.android.ui.photos.PhotoRejected
import com.whumpo.android.ui.photos.PreparedPhoto
import com.whumpo.android.ui.state.UiState
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.api.MobileApiException
import com.whumpo.core.api.Photo
import com.whumpo.core.api.Project
import com.whumpo.core.api.SiteVersion
import com.whumpo.core.api.newRequestId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectRow(
    val id: String,
    val name: String,
    val purpose: String,
    val status: StatusChip,
    val website: StatusChip,
    val app: StatusChip,
    val updated: TimeLabel,
)

/** [nextCursor] is non-null while more projects exist. */
data class ProjectList(
    val rows: List<ProjectRow>,
    val nextCursor: String?,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
)

data class VersionRow(val id: String, val component: String, val saved: TimeLabel)

data class ProjectDetail(val project: ProjectRow, val versions: List<VersionRow>)

data class PhotoRow(val id: String, val name: String, val summary: String, val status: StatusChip)

enum class UploadPhase { Preparing, Uploading, Failed }

/** One photo the owner is adding. A failed upload keeps its bytes and ID, so [canRetry] repeats the same request. */
data class UploadRow(val id: String, val phase: UploadPhase, val message: String? = null, val canRetry: Boolean = false)

data class PhotosState(
    val photos: UiState<List<PhotoRow>> = UiState.Loading,
    val uploads: List<UploadRow> = emptyList(),
)

/**
 * Projects list, the selected project's detail with versions, and its photos.
 * Reads go through [api]; the only write is a photo upload, which carries a caller-owned upload ID.
 * Selection is kept here so it survives rotation; the screen decides how to lay it out.
 */
class ProjectsViewModel(
    private val api: MobileApiClient,
    private val preparer: PhotoPreparer,
    private val store: UploadStore = NoUploadStore,
    private val clock: () -> Instant = { Clock.System.now() },
) : ViewModel() {
    private val _list = MutableStateFlow<UiState<ProjectList>>(UiState.Loading)
    val list: StateFlow<UiState<ProjectList>> = _list.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _detail = MutableStateFlow<UiState<ProjectDetail>>(UiState.Loading)
    val detail: StateFlow<UiState<ProjectDetail>> = _detail.asStateFlow()

    private val _photos = MutableStateFlow(PhotosState())
    val photos: StateFlow<PhotosState> = _photos.asStateFlow()

    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var photosJob: Job? = null

    /**
     * Every upload on this phone for the signed-in account: its project, its row and, once prepared, the exact bytes to repeat.
     * One map, so releasing a row releases its bytes. Cleared whenever the account changes.
     */
    private class Entry(val projectId: String, val photo: PreparedPhoto?, val row: UploadRow, val gen: Int)

    private val entries = linkedMapOf<String, Entry>()

    /** Bumps on every account change; work that started under an older value drops itself instead of crossing accounts. */
    private var generation = 0

    /** The account the store last reported (an opaque key). Saves are made for this account only. */
    private var account: Any? = null

    init {
        viewModelScope.launch {
            store.account.collectLatest { who ->
                // A different account (or sign-out): forget everything the last one had on screen or in memory, then start
                // clean for the new one. Uploads left by an earlier process come back as retries of the same request.
                generation++
                account = who
                listJob?.cancel()
                detailJob?.cancel()
                photosJob?.cancel()
                entries.clear()
                _selected.value = null
                _detail.value = UiState.Loading
                _photos.value = PhotosState()
                refresh()
                restore()
                publishUploads()
            }
        }
    }

    /** Stored uploads whose project is gone (or whose ID is not a project ID) can never succeed: drop them, free their slot. */
    private suspend fun restore() {
        val gen = generation
        val stored = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        for (projectId in stored.map { it.projectId }.distinct()) {
            val gone = try {
                api.getProject(projectId)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: MobileApiException.NotFound) {
                true
            } catch (_: IllegalArgumentException) {
                true
            } catch (_: Exception) {
                false // offline or busy: keep it and let the owner retry later
            }
            if (gen != generation) return
            stored.filter { it.projectId == projectId }.forEach { u ->
                if (gone) {
                    store.remove(u.id)
                } else {
                    entries[u.id] = Entry(u.projectId, PreparedPhoto(u.name, u.jpeg), UploadRow(u.id, UploadPhase.Failed, INTERRUPTED, canRetry = true), gen)
                }
            }
        }
    }

    fun refresh() {
        listJob?.cancel()
        _list.value = UiState.Loading
        listJob = viewModelScope.launch {
            _list.value = loadUi<ProjectList>("your projects", { it.rows.isEmpty() }) { fetchPage(null) }
        }
    }

    fun loadMore() {
        val current = (_list.value as? UiState.Content)?.value ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        _list.value = UiState.Content(current.copy(loadingMore = true, moreFailed = false))
        listJob = viewModelScope.launch {
            _list.value = try {
                val page = fetchPage(cursor)
                UiState.Content(ProjectList(current.rows + page.rows, page.nextCursor))
            } catch (e: CancellationException) {
                throw e
            } catch (_: MobileApiException) {
                UiState.Content(current.copy(loadingMore = false, moreFailed = true))
            }
        }
    }

    fun select(projectId: String?) {
        if (projectId == _selected.value) return
        _selected.value = projectId
        detailJob?.cancel()
        photosJob?.cancel()
        _photos.value = PhotosState()
        publishUploads()
        if (projectId != null) {
            loadDetail(projectId)
            loadPhotos(projectId)
        }
    }

    fun retryDetail() {
        _selected.value?.let { loadDetail(it) }
    }

    fun refreshPhotos() {
        _selected.value?.let { loadPhotos(it) }
    }

    /** The owner picked [source] (a platform handle). One new upload ID per pick; retries reuse it. */
    fun addPhoto(source: String) {
        val projectId = _selected.value ?: return
        val uploadId = newRequestId()
        val gen = generation
        val owner = account
        setUpload(uploadId, Entry(projectId, null, UploadRow(uploadId, UploadPhase.Preparing), gen))
        viewModelScope.launch {
            val prepared = try {
                preparer.prepare(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhotoRejected) {
                if (gen == generation) setUpload(uploadId, Entry(projectId, null, UploadRow(uploadId, UploadPhase.Failed, e.message, canRetry = false), gen))
                return@launch
            } catch (_: Exception) {
                // A hostile or broken handle (a provider that throws, a revoked grant): refuse it, never crash.
                if (gen == generation) setUpload(uploadId, Entry(projectId, null, UploadRow(uploadId, UploadPhase.Failed, "That photo can't be read. Choose it again.", canRetry = false), gen))
                return@launch
            }
            // The account changed while the photo was being prepared: these bytes belong to the old one. Drop them.
            if (gen != generation) return@launch
            setUpload(uploadId, Entry(projectId, prepared, UploadRow(uploadId, UploadPhase.Preparing), gen))
            // Keep the exact bytes before the first request, so a killed process can repeat it. If they cannot be kept (full,
            // a Keystore failure, the account just changed) the upload still goes ahead; it just cannot outlive this process.
            try {
                store.save(PendingUpload(uploadId, projectId, prepared.name, prepared.jpeg), owner)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (gen != generation) {
                store.remove(uploadId)
                return@launch
            }
            send(uploadId)
        }
    }

    /** An explicit owner retry: same ID, same bytes, same name. */
    fun retryUpload(uploadId: String) {
        val e = entries[uploadId] ?: return
        if (e.row.phase != UploadPhase.Failed || !e.row.canRetry || e.photo == null) return
        viewModelScope.launch { send(uploadId) }
    }

    fun dismissUpload(uploadId: String) {
        entries.remove(uploadId)
        publishUploads()
        viewModelScope.launch { store.remove(uploadId) }
    }

    private suspend fun send(uploadId: String) {
        val e0 = entries[uploadId] ?: return
        val photo = e0.photo ?: return
        val projectId = e0.projectId
        val gen = e0.gen
        setUpload(uploadId, Entry(projectId, photo, UploadRow(uploadId, UploadPhase.Uploading), gen))
        try {
            api.uploadPhoto(projectId, uploadId, photo.name, photo.jpeg)
            uploaded(uploadId, projectId, gen)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MobileApiException) {
            if (e is MobileApiException.Transport && e.outcomeUnknown) {
                resolveLostReply(uploadId, projectId, photo, gen)
            } else {
                val retry = e is MobileApiException.RateLimited || e is MobileApiException.Unavailable
                val row = UploadRow(uploadId, UploadPhase.Failed, uploadMessage(e), canRetry = retry)
                // Refused for good: the bytes can never be retried, so they are released first and only then is the failure shown.
                if (!retry) store.remove(uploadId)
                setUpload(uploadId, Entry(projectId, if (retry) photo else null, row, gen))
            }
        }
    }

    /** The reply was lost: read the upload instead of guessing. Not found means nothing committed. */
    private suspend fun resolveLostReply(uploadId: String, projectId: String, photo: PreparedPhoto, gen: Int) {
        try {
            api.getPhotoUpload(projectId, uploadId)
            uploaded(uploadId, projectId, gen)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MobileApiException.NotFound) {
            setUpload(uploadId, Entry(projectId, photo, UploadRow(uploadId, UploadPhase.Failed, "The upload didn't finish. Nothing was added.", canRetry = true), gen))
        } catch (_: MobileApiException) {
            setUpload(uploadId, Entry(projectId, photo, UploadRow(uploadId, UploadPhase.Failed, "We couldn't confirm the upload. Try again; it won't be added twice.", canRetry = true), gen))
        }
    }

    private suspend fun uploaded(uploadId: String, projectId: String, gen: Int) {
        if (gen != generation) return
        entries.remove(uploadId)
        publishUploads()
        store.remove(uploadId)
        if (_selected.value == projectId) loadPhotos(projectId)
    }

    private fun uploadMessage(e: MobileApiException): String = when (e) {
        is MobileApiException.PayloadTooLarge -> "That photo is too large. Choose a less detailed photo."
        is MobileApiException.UnsupportedMediaType, is MobileApiException.ValidationFailed -> "That photo can't be used."
        is MobileApiException.RateLimited -> e.userMessage("the photo").let { "${it.first} ${it.second}" }
        is MobileApiException.ProofRequired, is MobileApiException.IdentityChanged -> "You've been signed out. Sign in again to add photos."
        is MobileApiException.Unavailable -> e.message ?: "The photo wasn't added. Nothing was changed." // the server's plain quota/storage message (contract: 503 unavailable)
        else -> "The photo wasn't added. Nothing was changed."
    }

    private fun setUpload(uploadId: String, entry: Entry) {
        if (entry.gen != generation) return // late work from another account never touches this one
        entries[uploadId] = entry
        publishUploads()
    }

    private fun publishUploads() {
        val shown = entries.values.filter { it.projectId == _selected.value }.map { it.row }
        _photos.update { it.copy(uploads = shown) }
    }

    private suspend fun fetchPage(cursor: String?): ProjectList {
        val page = api.listProjects(cursor = cursor)
        val now = clock()
        return ProjectList(page.items.map { it.toRow(now) }, page.nextCursor)
    }

    private fun loadDetail(projectId: String) {
        detailJob?.cancel()
        _detail.value = UiState.Loading
        detailJob = viewModelScope.launch {
            val result = loadUi<ProjectDetail>("this project") {
                val now = clock()
                val project = api.getProject(projectId).project.toRow(now)
                ProjectDetail(project, api.listVersions(projectId).items.map { it.toRow(now) })
            }
            if (_selected.value == projectId) _detail.value = result
        }
    }

    private fun loadPhotos(projectId: String) {
        photosJob?.cancel()
        _photos.update { it.copy(photos = UiState.Loading) }
        photosJob = viewModelScope.launch {
            val result = loadUi<List<PhotoRow>>("photos", { it.isEmpty() }) { api.listPhotos(projectId).items.map { it.toRow() } }
            if (_selected.value == projectId) _photos.update { it.copy(photos = result) }
        }
    }
}

private const val INTERRUPTED = "This upload was interrupted. Try again; it won't be added twice."

private fun Project.toRow(now: Instant): ProjectRow = ProjectRow(
    id = id,
    name = name,
    purpose = purpose,
    status = when {
        siteLive -> StatusChip("Live", Tone.Ready)
        status == "draft" -> StatusChip("Draft", Tone.Neutral)
        else -> StatusChip(status.replace('_', ' ').replaceFirstChar { it.uppercase() }, Tone.Progress)
    },
    website = if (siteLive) StatusChip("Live", Tone.Ready) else StatusChip("Not live yet", Tone.Neutral),
    app = if (backendLive) StatusChip("Live", Tone.Ready) else StatusChip("Not live yet", Tone.Neutral),
    updated = timeLabel(updatedAt, now, "Updated"),
)

private fun SiteVersion.toRow(now: Instant) = VersionRow(id, componentId, timeLabel(createdAt, now, "Saved"))

private fun Photo.toRow() = PhotoRow(
    id = id,
    name = name,
    summary = "$width × $height, ${sizeLabel(size)}",
    status = when {
        safetyStatus == "blocked" -> StatusChip("Not approved", Tone.Attention)
        usable -> StatusChip("Ready", Tone.Ready)
        else -> StatusChip("Checking", Tone.Progress)
    },
)

private fun sizeLabel(bytes: Int): String =
    if (bytes < 1_048_576) "${maxOf(1, bytes / 1024)} KB" else (bytes * 10L / 1_048_576).let { "${it / 10}.${it % 10} MB" }
