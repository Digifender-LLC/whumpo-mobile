package com.whumpo.android.ui.share

import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something another app handed over through the system share sheet. Holding it sends and fetches nothing. */
sealed interface SharedItem {
    /** A photo, as a platform handle (a content URI on Android). */
    data class Photo(val source: String) : SharedItem

    /** Text or a link. It is only ever copied into the Chat draft; the app never opens or fetches it. */
    data class Text(val text: String) : SharedItem
}

/** A small picture of the shared photo, or why there is none. Held in memory only. */
sealed interface PreviewState {
    /** Not asked for yet (or cleared). */
    data object Idle : PreviewState
    data object Loading : PreviewState
    class Ready(val image: ImageBitmap) : PreviewState

    /** Corrupt, too large, the wrong type, too slow or unreadable. The dialog still works. */
    data object Unavailable : PreviewState
}

/**
 * Reads a shared photo on this phone and returns a thumbnail, or null when it cannot be shown. It never uploads, stores or
 * caches anything: the file is read and decoded in memory. [maxSidePx] bounds the longer side of the result.
 */
interface PhotoPreviewer {
    suspend fun thumbnail(source: String, maxSidePx: Int): ImageBitmap?
}

/**
 * The newest shared item not yet answered. It waits behind the sign-in gate and in memory only: if the process dies
 * before the owner confirms, it is gone and nothing was sent. The photo preview lives here too, so one [clear] (dismiss,
 * confirm, sign-out, another account) drops the item and its thumbnail together.
 */
class ShareInbox {
    private val item = MutableStateFlow<SharedItem?>(null)
    val pending: StateFlow<SharedItem?> = item.asStateFlow()

    private val thumbnail = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val preview: StateFlow<PreviewState> = thumbnail.asStateFlow()

    fun offer(next: SharedItem) {
        thumbnail.value = PreviewState.Idle
        item.value = next
    }

    fun clear() {
        item.value = null
        thumbnail.value = PreviewState.Idle
    }

    /**
     * Builds the preview for the pending photo, once. A result that arrives after the item was cleared or replaced is
     * discarded, so a slow file can never put a thumbnail back after dismiss or sign-out.
     */
    suspend fun loadPreview(previewer: PhotoPreviewer, maxSidePx: Int) {
        val photo = item.value as? SharedItem.Photo ?: return
        if (thumbnail.value != PreviewState.Idle) return
        thumbnail.value = PreviewState.Loading
        val image = try {
            previewer.thumbnail(photo.source, maxSidePx)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            if (item.value == photo && thumbnail.value == PreviewState.Loading) thumbnail.value = PreviewState.Idle
            throw e
        } catch (_: Exception) {
            null
        }
        if (item.value == photo) thumbnail.value = if (image != null) PreviewState.Ready(image) else PreviewState.Unavailable
    }

    /**
     * Drops a pending share whenever the signed-in account stops being the one it arrived for: sign-out or a different
     * account. Going from signed out to signed in keeps it (a share can arrive before sign-in). Collects until cancelled.
     */
    suspend fun clearWhenAccountChanges(accounts: Flow<Any?>) {
        var previous: Any? = null
        accounts.collect { next ->
            if (previous != null && next != previous) clear()
            previous = next
        }
    }
}

/**
 * The one place that decides whether a shared item may be acted on. A photo needs a chosen project, and nothing happens
 * until [confirm]; a second confirm does nothing, so a double tap cannot add a photo twice.
 */
class ShareFlow(
    val item: SharedItem,
    private val onAddPhoto: (projectId: String) -> Unit,
    private val onAddToDraft: (text: String) -> Unit,
) {
    var chosen by mutableStateOf<String?>(null)
        private set

    private var done = false

    fun choose(projectId: String) {
        chosen = projectId
    }

    val canConfirm: Boolean get() = !done && (item is SharedItem.Text || chosen != null)

    /** True when the item was acted on. */
    fun confirm(): Boolean {
        if (!canConfirm) return false
        done = true
        when (item) {
            is SharedItem.Photo -> onAddPhoto(chosen!!)
            is SharedItem.Text -> onAddToDraft(item.text)
        }
        return true
    }
}
