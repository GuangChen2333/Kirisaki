// SPDX-License-Identifier: GPL-3.0-only

package moe.nyaneko.kirisaki

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.ResultReceiver

/** Explicit signed request, ephemeral Binder capability, and a UID-checked reply. */
object StateBridge {
    fun request(context: Context) {
        val app = context.applicationContext
        val uid = runCatching { app.packageManager.getApplicationInfo(TARGET_PACKAGE, 0).uid }.getOrNull() ?: return
        // No Handler: onReceiveResult executes on the incoming Binder thread, preserving caller UID.
        val reply = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (Binder.getCallingUid() != uid || resultData == null) return
                val identity = Binder.clearCallingIdentity()
                try {
                    runCatching { app.contentResolver.call(STATE_URI, "acceptSnapshot", null, resultData) }
                } finally {
                    Binder.restoreCallingIdentity(identity)
                }
            }
        }
        app.sendBroadcast(Intent(STATE_REQUEST_ACTION).setPackage(TARGET_PACKAGE).putExtra("reply", reply))
    }
}
