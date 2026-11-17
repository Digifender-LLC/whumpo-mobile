package com.whumpo.android.core

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.whumpo.android.ui.home.CoreTaskCalendarActions
import com.whumpo.core.auth.CoreOrganizationSessionController
import com.whumpo.core.auth.CoreTaskCalendarReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun CoreWorkTaskCalendar(
    controller: CoreOrganizationSessionController,
    epoch: Long,
    draft: CoreTaskCalendarReview,
    selectionCurrent: () -> Boolean,
) {
    val context = LocalContext.current
    val actions = rememberCoroutineScope()
    var review by remember { mutableStateOf<CoreTaskCalendarReview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun current(): Boolean = selectionCurrent() && controller.commitIfCurrent(epoch) {}

    fun check(expected: CoreTaskCalendarReview, open: Boolean) {
        if (busy || !current()) return
        busy = true
        notice = null
        actions.launch {
            try {
                val unchanged = expected.verify(::current, controller::task)
                currentCoroutineContext().ensureActive()
                controller.commitIfCurrent(epoch) {
                    if (!selectionCurrent()) return@commitIfCurrent
                    if (!unchanged) {
                        review = null
                        notice = "This deadline changed. Reopen the task to review it again."
                    } else if (!open) {
                        review = expected
                    } else {
                        review = null
                        try {
                            context.startActivity(calendarEditor(expected))
                        } catch (_: ActivityNotFoundException) {
                            notice = "No calendar editor is available on this device."
                        } catch (_: SecurityException) {
                            notice = "The calendar editor could not be opened."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current()) {
                    review = null
                    notice = "Could not verify the current deadline."
                }
            } finally {
                busy = false
            }
        }
    }

    CoreTaskCalendarActions(review, busy, notice,
        onReview = { check(draft, open = false) },
        onOpen = { review?.let { check(it, open = true) } },
        onCancel = { review = null; notice = null },
    )
}

private fun calendarEditor(review: CoreTaskCalendarReview): Intent =
    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, review.title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, review.startMillis)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, review.endMillis)
