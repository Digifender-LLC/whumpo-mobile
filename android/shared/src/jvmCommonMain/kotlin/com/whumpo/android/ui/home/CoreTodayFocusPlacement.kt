package com.whumpo.android.ui.home

import androidx.compose.runtime.Composable
import com.whumpo.core.api.CoreTodaySection
import com.whumpo.core.api.CoreTodaySiteActivity

/** Keep pending decisions above optional shortcuts and routine activity. */
@Composable
fun CoreTodayFocusPlacement(
    site: CoreTodaySection<CoreTodaySiteActivity>?,
    websiteDecisions: @Composable (CoreTodaySection<CoreTodaySiteActivity>) -> Unit,
    focus: @Composable () -> Unit,
    verifiedResults: @Composable () -> Unit,
    websiteReceipts: @Composable (CoreTodaySection<CoreTodaySiteActivity>) -> Unit,
) {
    val (reviews, receipts) = site?.items.orEmpty().partition { it.source.kind.review }
    if (reviews.isNotEmpty()) websiteDecisions(CoreTodaySection(reviews, false))
    focus()
    verifiedResults()
    websiteReceipts(CoreTodaySection(receipts, site?.truncated == true))
}
