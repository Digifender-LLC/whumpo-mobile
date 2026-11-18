package com.whumpo.android.core

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.whumpo.android.R
import com.whumpo.core.auth.CoreSharedDraftItem
import java.lang.ref.WeakReference

/** A local-only Sharesheet target. Only an opaque delivery ID crosses into the private activity. */
class CoreShareDraftActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            val item = readItem(intent)
            if (item == null) {
                Toast.makeText(this, R.string.core_share_unsupported, Toast.LENGTH_LONG).show()
            } else {
                val target = CoreShareDelivery.current()
                val id = target?.prepareShare(item)
                if (target == null || id == null) {
                    Toast.makeText(this, R.string.core_share_unavailable, Toast.LENGTH_LONG).show()
                } else {
                    try {
                        startActivity(Intent(this, CorePreviewActivity::class.java)
                            .setAction(CoreShareDelivery.ACTION)
                            .putExtra(CoreShareDelivery.ID, id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } catch (_: RuntimeException) {
                        target.cancelShareDelivery(id)
                        Toast.makeText(this, R.string.core_share_unavailable, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        finish()
    }

    private fun readItem(intent: Intent): CoreSharedDraftItem? = try {
        if (intent.action != Intent.ACTION_SEND || intent.type != "text/plain" ||
            intent.data != null || intent.hasExtra(Intent.EXTRA_STREAM)) null
        else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let(CoreSharedDraftItem::parse)
    } catch (_: RuntimeException) {
        null
    }
}

/** Tracks only the already-open private target. The item stays with that exact activity. */
internal object CoreShareDelivery {
    const val ACTION = "com.whumpo.android.local.share.DRAFT"
    const val ID = "com.whumpo.android.local.share.DELIVERY"
    private var preview = WeakReference<CorePreviewActivity>(null)

    fun attach(activity: CorePreviewActivity) { preview = WeakReference(activity) }

    fun detach(activity: CorePreviewActivity) {
        if (preview.get() === activity) preview.clear()
    }

    fun current(): CorePreviewActivity? = preview.get()
}
