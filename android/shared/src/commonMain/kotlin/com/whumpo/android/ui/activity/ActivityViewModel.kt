package com.whumpo.android.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whumpo.android.ui.common.TimeLabel
import com.whumpo.android.ui.common.ageLabel
import com.whumpo.android.ui.common.loadUi
import com.whumpo.android.ui.state.UiState
import com.whumpo.core.api.ActivityItem
import com.whumpo.core.api.MobileApiClient
import com.whumpo.core.api.MobileApiException
import com.whumpo.core.push.DeviceNotifications
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ActivityRow(val id: String, val summary: String, val action: String, val projectId: String?, val time: TimeLabel)

data class ActivityList(
    val rows: List<ActivityRow>,
    val nextCursor: String?,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
)

/** What opening a notification found. [available] false means the event is gone or not the owner's. */
data class EventNotice(val text: String, val available: Boolean)

/** The activity feed, plus the notice shown when a notification is opened at an event. */
class ActivityViewModel(
    private val api: MobileApiClient,
    private val notifications: DeviceNotifications,
    private val clock: () -> Instant = { Clock.System.now() },
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ActivityList>>(UiState.Loading)
    val state: StateFlow<UiState<ActivityList>> = _state.asStateFlow()

    private val _notice = MutableStateFlow<EventNotice?>(null)
    val notice: StateFlow<EventNotice?> = _notice.asStateFlow()

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            _state.value = loadUi<ActivityList>("your activity", { it.rows.isEmpty() }) { fetchPage(null) }
        }
    }

    fun loadMore() {
        val current = (_state.value as? UiState.Content)?.value ?: return
        val cursor = current.nextCursor ?: return
        if (current.loadingMore) return
        _state.value = UiState.Content(current.copy(loadingMore = true, moreFailed = false))
        viewModelScope.launch {
            _state.value = try {
                val page = fetchPage(cursor)
                UiState.Content(ActivityList(current.rows + page.rows, page.nextCursor))
            } catch (e: CancellationException) {
                throw e
            } catch (_: MobileApiException) {
                UiState.Content(current.copy(loadingMore = false, moreFailed = true))
            }
        }
    }

    /** A notification was tapped: read the event's current state, then refresh the feed. Nothing is sent or approved. */
    fun openEvent(eventId: String) {
        viewModelScope.launch {
            _notice.value = try {
                val event = notifications.event(eventId)
                if (event.state == "current" && event.destination != null) {
                    EventNotice(
                        when (event.kind) {
                            "review_pending" -> "A change is ready for your review."
                            "operation_completed" -> "Work Whumpo was doing has finished."
                            else -> "Something needs your attention."
                        },
                        available = true,
                    )
                } else {
                    UNAVAILABLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: MobileApiException.NotFound) {
                UNAVAILABLE
            } catch (_: MobileApiException) {
                EventNotice("Couldn't check that notification. Nothing was changed.", available = false)
            }
            refresh()
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private suspend fun fetchPage(cursor: String?): ActivityList {
        val page = api.listActivity(cursor = cursor)
        val now = clock()
        return ActivityList(page.items.map { it.toRow(now) }, page.nextCursor)
    }

    private companion object {
        val UNAVAILABLE = EventNotice("This item isn't available anymore.", available = false)
    }
}

private fun ActivityItem.toRow(now: Instant) = ActivityRow(
    id = id,
    summary = summary,
    action = action.replace('.', ' ').replace('_', ' ').replaceFirstChar { it.uppercase() },
    projectId = projectId,
    time = ageLabel(createdAt, now),
)
