package com.whumpo.wear

import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date

/** A small native Wear destination for taps from the Today tile. */
class TodayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        content.addView(text(getString(R.string.tile_name), 22f, heading = true), spaced())

        when (val state = todayTileState(TodayTileSource.snapshot(this), System.currentTimeMillis())) {
            TodayTileState.Unavailable -> content.addView(
                text(getString(R.string.today_unavailable_detail), 16f), spaced(),
            )
            is TodayTileState.Available -> {
                if (state.counts.empty) {
                    content.addView(
                        text(
                            getString(R.string.today_empty),
                            16f,
                            description = getString(R.string.today_empty_accessibility),
                        ),
                        spaced(),
                    )
                } else {
                    content.addView(
                        text(getString(R.string.today_approvals_count, state.counts.approvals.display()), 18f),
                        spaced(),
                    )
                    content.addView(
                        text(getString(R.string.today_blocked_count, state.counts.blocked.display()), 18f),
                        spaced(),
                    )
                    content.addView(
                        text(getString(R.string.today_deadlines_count, state.counts.deadlines.display()), 18f),
                        spaced(),
                    )
                }
                val time = DateFormat.getTimeFormat(this).format(Date.from(state.generatedAt))
                content.addView(
                    text(
                        getString(R.string.today_updated, time),
                        14f,
                        description = getString(R.string.today_updated_accessibility, time),
                    ),
                )
            }
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    private fun text(value: String, sizeSp: Float, heading: Boolean = false, description: String? = null) = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(0xFFFFFFFF.toInt())
        isAccessibilityHeading = heading
        contentDescription = description ?: value.takeIf { heading }
    }

    private fun spaced() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = dp(12) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
