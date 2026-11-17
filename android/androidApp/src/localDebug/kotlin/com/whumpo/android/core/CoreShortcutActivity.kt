package com.whumpo.android.core

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.whumpo.core.auth.CoreStaticShortcut

/** Gives the launcher's static CLEAR_TASK flags a task separate from the connected preview. */
class CoreShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        if (savedInstanceState == null && CoreStaticShortcut.fromAction(action) != null) {
            startActivity(Intent(this, CorePreviewActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
